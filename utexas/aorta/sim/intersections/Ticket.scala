// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.sim.intersections

import utexas.aorta.map.{Turn, Graph}
import utexas.aorta.sim.EV_Turn
import utexas.aorta.sim.drivers.Agent

import utexas.aorta.common.{Util, cfg, StateWriter, StateReader, TurnID}

class Ticket(val a: Agent, val turn: Turn) extends Ordered[Ticket] {
  //////////////////////////////////////////////////////////////////////////////
  // State
  
  // Don't initially know: accept_tick, done_tick, cost_paid.
  // TODO keep state properly, dont shuffle it into this >_<
  var stat = EV_Turn(a, turn.vert, a.sim.tick, -1.0, -1.0, 0.0)

  // If we interrupt a reservation successfully, don't let people block us.
  var is_interruption = false

  // When our turn first becomes not blocked while we're stopped, start the
  // timer.
  private var waiting_since = -1.0

  //////////////////////////////////////////////////////////////////////////////
  // Meta

  def serialize(w: StateWriter) {
    // Agent is implied, since we belong to them
    w.int(turn.id.int)
    w.double(stat.req_tick)
    w.double(stat.accept_tick)
    w.double(stat.done_tick)
    w.double(stat.cost_paid)
    w.bool(is_interruption)
    w.double(waiting_since)
  }

  //////////////////////////////////////////////////////////////////////////////
  // Actions

  def approve() {
    stat = stat.copy(accept_tick = a.sim.tick)
    // Allocate a spot for them. This must be called when the turn isn't
    // blocked.
    // TODO this messes up parallelism again...
    if (!is_interruption) {
      // When we became the interrupting ticket, we grabbed the slot.
      turn.to.queue.allocate_slot
    }
  }

  def cancel() {
    Util.assert_eq(is_approved, false)
    a.tickets.remove(this)
    intersection.cancel_turn(this)
  }

  // To enforce liveness, policies shouldn't accept a turn that can't certainly
  // be finished. Once a turn is accepted, lane-changing and spawning and such
  // have to maintain the stability of this property.
  // This is an action because it touches the waiting_since state.
  def turn_blocked(): Boolean = {
    val target = turn.to
    val intersection = turn.vert.intersection

    // They might not finish LCing before an agent in the new or old lane
    // stalls.
    if (a.is_lanechanging) {
      return true
    }

    // Lane-changing and spawning respect allocations of capacity too, so this
    // tells us if we can finish the turn.
    if (!target.queue.slot_avail) {
      return true
    }

    val steps = a.route.steps_to(a.at.on, turn.vert)
    // TODO efficiency... dont search behind us, only look one person ahead once
    // this is really an enforced invariant
    val blocked_cur_step = steps.head.queue.all_agents.find(
      agent => agent.at.dist > a.at.dist && !agent.wont_block(intersection)
    ).isDefined
    if (blocked_cur_step) {
      return true
    }

    val blocked_future_step = steps.tail.find(step => step.queue.all_agents.find(
      agent => !agent.wont_block(intersection)
    ).isDefined).isDefined
    if (blocked_future_step) {
      return true
    }

    // We're clear!
    if (waiting_since == -1.0 && a.is_stopped) {
      waiting_since = a.sim.tick
    }
    return false
  }

  //////////////////////////////////////////////////////////////////////////////
  // Queries

  override def toString = s"Ticket($a, $turn, approved? $is_approved. interrupt? $is_interruption)"

  // TODO if an agent ever loops and requests the same turn before clearing the
  // prev one, gonna have a bad time!
  override def compare(other: Ticket) = Ordering[Tuple2[Int, Int]].compare(
    (a.id.int, turn.id.int), (other.a.id.int, other.turn.id.int)
  )

  def intersection = turn.vert.intersection

  def is_approved = stat.accept_tick != -1.0

  def how_long_waiting =
    if (waiting_since == -1.0)
      0.0
    else
      a.sim.tick - waiting_since

  def earliest_start() =
    a.how_far_away(turn.vert.intersection) / a.at.on.speed_limit
  def earliest_finish() =
    (a.how_far_away(turn.vert.intersection) + turn.length) / math.min(a.at.on.speed_limit, turn.speed_limit)

  // This isn't really optimistic or pessimistic; we could be going the speed
  // limit or not
  def start_at_cur_speed() =
    a.how_far_away(turn.vert.intersection) / math.max(a.speed, cfg.epsilon)
  def finish_at_cur_speed() =
    (a.how_far_away(turn.vert.intersection) + turn.length) / math.max(a.speed, cfg.epsilon)

  def close_to_start =
    if (a.at.on == turn.from) {
      a.our_lead match {
        // Are we following reasonably closely?
        // TODO better math to account for following at speed
        case Some(other) => other.at.dist - a.at.dist <= cfg.follow_dist * 10.0
        // If we're the head, just be close to starting
        case None => earliest_start <= 5.0
      }
    } else {
      // When we register early or lookahead over small steps, just demand we'll
      // arrive at the turn soon
      earliest_start <= 10.0
    }

  // If we're part of gridlock and we have a choice, bail out.
  def should_cancel() =
    turn.from.next_turns.size > 1 && Intersection.detect_gridlock(turn)
}

object Ticket {
  def unserialize(r: StateReader, a: Agent, graph: Graph): Ticket = {
    val ticket = new Ticket(a, graph.turns(new TurnID(r.int)))
    ticket.stat = ticket.stat.copy(
      req_tick = r.double, accept_tick = r.double, done_tick = r.double,
      cost_paid = r.double
    )
    ticket.is_interruption = r.bool
    ticket.waiting_since = r.double
    return ticket
  }
}
