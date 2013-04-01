// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.sim

import scala.collection.mutable.ListBuffer

import utexas.aorta.map.{Edge, Turn, Traversable, DirectedRoad}
import utexas.aorta.analysis.Stats

import utexas.aorta.{Util, Common, cfg, Physics}

abstract class Behavior(a: Agent) {
  // asked every tick after everybody has moved
  def choose_action(): Action
  // only queried when the agent reaches a vertex
  def choose_turn(e: Edge): Turn
  // every time the agent moves to a new traversable
  def transition(from: Traversable, to: Traversable)
  // just for debugging
  def dump_info()
  def wants_to_lc(): Boolean = target_lane != null && target_lane.isDefined

  // As an optimization and to keep some stats on how successful lane-changing
  // is, remember the adjacent lane we'd like to switch into.
  // Start null to trigger the initial case of resetting it. Have to do it at
  // "sim time" when agent's actually first moving, otherwise the route might
  // not be ready to answer us.
  var target_lane: Option[Edge] = null
}

// Never speeds up from rest, so effectively never does anything
class IdleBehavior(a: Agent) extends Behavior(a) {
  def choose_action(): Action = Act_Set_Accel(0)

  def choose_turn(e: Edge) = e.next_turns.head

  def transition(from: Traversable, to: Traversable) = {}

  def dump_info() = {
    Util.log("Idle behavior")
  }
}

// Reactively avoids collisions and obeys intersections by doing a conservative
// analysis of the next few steps.
class LookaheadBehavior(a: Agent, route: Route) extends Behavior(a) {
  private def reset_target_lane(base: Edge) = {
    target_lane = None
    // Did lookahead previously schedule a turn for this road? We can't
    // lane-change, then! Already committed.
    val i = base.to.intersection
    if (!a.involved_with(i)) {
      base match {
        case e: Edge => {
          val target = route.pick_lane(e)
          // Tough liveness guarantees... give up early.
          // TODO move this check to give up to react()
          if (target != base && a.can_lc_without_blocking(target)) {
            target_lane = Some(target)
          }
        }
        case _ =>
      }
    }
  }

  // Don't commit to turning from some lane in lookahead unless we're there are
  // LCing could still happen, or if it's a future edge with no other lanes.
  private def committed_to_lane(step: LookaheadStep) = step.at match {
    case e if e == a.at.on => !target_lane.isDefined
    case e: Edge => e.other_lanes.size == 1
    case t: Turn => throw new Exception(s"Requesting a turn from a turn $step?!")
  }
  
  def choose_turn(e: Edge) = route.pick_turn(e)
  
  def transition(from: Traversable, to: Traversable) = {
    route.transition(from, to)
    // reset state
    to match {
      case e: Edge => reset_target_lane(e)
      case _ => target_lane = None
    }
  }

  def dump_info() = {
    Util.log("Route-following behavior")
    Util.log(s"Target lane: $target_lane")
    route.dump_info
  }

  def choose_action(): Action = {
    // Do we want to lane change?
    // TODO 1) discretionary lane changing to pass people
    // TODO 2) routes can lookahead a bit to tell us to lane-change early
    
    // TODO awkward way to bootstrap this.
    if (target_lane == null) {
      // Should be an edge, since we start on edges.
      reset_target_lane(a.at.on.asInstanceOf[Edge])
    }

    // Commit to not lane-changing if it's too late. That way, we can decide on
    // a turn.
    target_lane match {
      case Some(target) => {
        // No room? Fundamentally impossible
        // Somebody in the way? If we're stalled and somebody's in the way,
        // we're probably waiting in a queue. Don't waste time hoping, grab a
        // turn now.
        if (!a.room_to_lc(target) || (a.speed == 0.0 && !a.can_lc_without_crashing(target))) {
          target_lane = None
        }
      }
      case _ =>
    }

    // Try a fast-path!
    /*val no_lc = (!a.is_lanechanging) && (!target_lane.isDefined)
    val not_near_end = a.at.dist_left >= a.max_lookahead_dist + cfg.end_threshold // TODO +buf?
    val next_i = a.at.on match {
      case e: Edge => e.to.intersection
      case t: Turn => t.to.to.intersection
    }
    val already_registered = a.involved_with(next_i)
    val lead = a.our_lead
    val not_tailing = lead match {
      case Some(l) =>
        // TODO or just stopping dist at max next speed?
        (l.at.dist - a.at.dist) >= (a.max_lookahead_dist + cfg.follow_dist)
      case None => true
    }
    val at_speed = a.speed == a.at.on.speed_limit
    // TODO more fast paths that dont do full analysis
    // TODO go back and prove these are equivalent to the original semantics
    if (no_lc && not_near_end && already_registered) {
      if (not_tailing && at_speed) {
        return Act_Set_Accel(0)
      } else if (not_tailing) {
        // so we're not at speed
        return Act_Set_Accel(
          math.min(a.accel_to_achieve(a.at.on.speed_limit), a.max_accel)
        )
      } else if (at_speed) {
        // so we're tailing
        return Act_Set_Accel(math.max(
          accel_to_follow(lead.get, lead.get.at.dist - a.at.dist),
          -a.max_accel
        ))
      }
    }*/

    return max_safe_accel
  }

  // Returns Act_Set_Accel almost always.
  def max_safe_accel(): Action = {
    // Since we can't react instantly, we have to consider the worst-case of the
    // next tick, which happens when we speed up as much as possible this tick.

    // the output.
    var accel_for_stop: Option[Double] = None
    var accel_for_agent: Option[Double] = None
    var accel_for_lc_agent: Option[Double] = None
    var min_speed_limit = Double.MaxValue
    var done_with_route = false

    var step = new LookaheadStep(
      a.at.on, a.max_lookahead_dist, 0, a.at.dist_left, route
    )

    accel_for_lc_agent = constraint_lc_agent

    // If we don't have to stop for an intersection, keep caring about staying
    // far enough behind an agent. Once we have to stop somewhere, don't worry
    // about agents beyond that point.
    while (step != null && !accel_for_stop.isDefined) {
      if (!accel_for_agent.isDefined) {
        accel_for_agent = constraint_agent(step)
      }

      if (!accel_for_stop.isDefined) {
        constraint_stop(step) match {
          case Left(constraint) => accel_for_stop = constraint
          case Right(done) => done_with_route = true
        }
      }

      min_speed_limit = math.min(min_speed_limit, step.at.speed_limit)

      // Set the next step.
      step = step.next_step match {
        case Some(s) => s
        case None => null
      }
    }

    // TODO consider moving this first case to choose_action and not doing
    // lookahead when these premises hold true.
    return if (done_with_route) {
      Act_Done_With_Route()
    } else {
      val conservative_accel = List(
        accel_for_stop, accel_for_agent, accel_for_lc_agent,
        Some(a.accel_to_achieve(min_speed_limit)),
        // Don't forget physical limits
        Some(a.max_accel)
      ).flatten.min

      // As the very last step, clamp based on our physical capabilities.
      Act_Set_Accel(math.max(conservative_accel, -a.max_accel))
    }
  }

  // All constraint functions return a limiting acceleration, if relevant
  // Don't plow into people
  def constraint_agent(step: LookaheadStep): Option[Double] = {
    val follow_agent = if (a.at.on == step.at)
                         a.cur_queue.ahead_of(a)
                       else
                         step.at.queue.last
    return follow_agent match {
      // This happens when we grab the last person off the next step's queue
      // for lanechanging. Lookahead for lanechanging will change soon anyway,
      // for now just avoid this case. TODO
      case Some(other) if a == other => None  // TODO next to last?
      case Some(other) => {
        val dist_away = if (other.on(a.at.on))
                          other.at.dist - a.at.dist
                        else
                          step.dist_ahead + other.at.dist
        Some(accel_to_follow(other, dist_away))
      }
      case None => None
    }
  }

  // When we're lane-changing, lookahead takes care of the new path. But we
  // still have to pay attention to exactly one other agent: the one in front of
  // us on our old lane.
  def constraint_lc_agent(): Option[Double] = a.old_lane match {
    case Some(e) => e.queue.ahead_of(a) match {
      case Some(other) => {
        val dist_away = other.at.dist - a.at.dist
        Some(accel_to_follow(other, dist_away))
      }
      case None => None
    }
    case None => None
  }

  // Returns an optional acceleration, or 'true', which indicates the agent
  // is totally done.
  def constraint_stop(step: LookaheadStep): Either[Option[Double], Boolean] = {
    // Request a turn early?
    step.at match {
      case e: Edge if !route.done(e) && committed_to_lane(step) => {
        val i = e.to.intersection
        // TODO for multiple tickets at the same intersection... technically
        // should see if the specific ticket exists yet.
        if (!a.involved_with(i)) {
          val next_turn = route.pick_turn(e)
          val ticket = new Ticket(a, next_turn)
          a.tickets += ticket
          i.request_turn(ticket)
        }
      }
      case _ =>
    }

    // The goal is to stop in the range [length - end_threshold, length),
    // preferably right at that left border.

    if (step.predict_dist < step.this_dist - cfg.end_threshold) {
      return Left(None)
    }

    // end of this current step's edge, that is
    val dist_from_agent_to_end = step.dist_ahead + step.this_dist

    val can_go: Boolean = step.at match {
      // Don't stop at the end of a turn
      case t: Turn => true
      // Stop if we're arriving at destination
      case e: Edge if route.done(e) => false
      // Otherwise, ask the intersection
      case e: Edge => a.get_ticket(route.pick_turn(e)) match {
        case Some(ticket) => {
          if (ticket.is_approved) {
            true
          } else {
            impatient_turn(ticket, e)
            false
          }
        }
        case None => false
      }
    }
    if (can_go) {
      return Left(None)
    }

    // Are we completely done?
    val maybe_done = dist_from_agent_to_end <= cfg.end_threshold && a.speed == 0.0
    return a.at.on match {
      case e: Edge if route.done(e) && maybe_done => {
        Right(true)
      }
      case _ => {
        // We want to go the distance that puts us at length - end_threshold. If
        // we're already past that point (due to floating point imprecision, or
        // just because the edge is short), then try to cover enough distance to
        // get us to the start of the edge.
        val want_dist = math.max(
          step.dist_ahead, dist_from_agent_to_end - cfg.end_threshold
        )
        Left(Some(accel_to_end(want_dist)))
      }
    }
  }

  private def accel_to_follow(follow: Agent, dist_from_them_now: Double): Double = {
    val us_worst_dist = a.max_next_dist_plus_stopping
    val most_we_could_go = a.max_next_dist
    val least_they_could_go = follow.min_next_dist

    // TODO this optimizes for next tick, so we're playing it really
    // conservative here... will that make us fluctuate more?
    val projected_dist_from_them = dist_from_them_now - most_we_could_go + least_they_could_go
    val desired_dist_btwn = us_worst_dist + cfg.follow_dist

    // Positive = speed up, zero = go their speed, negative = slow down
    val delta_dist = projected_dist_from_them - desired_dist_btwn

    // Try to cover whatever the distance is
    return Physics.accel_to_cover(delta_dist, a.speed)
  }

  // Find an accel to travel want_dist and wind up with speed 0.
  private def accel_to_end(want_dist: Double): Double = {
    if (want_dist > 0.0) {
      if (a.speed > 0.0) {
        // d = (v_1)(t) + (1/2)(a)(t^2)
        // 0 = (v_1) + (a)(t)
        // Eliminating time yields the formula for accel below.

        // If this accel puts us past speed 0, it's fine, we just idle for the
        // remainder of the timestep.
        return (-1 * a.speed * a.speed) / (2 * want_dist)
      } else {
        // We have to accelerate so that we can get going, but not enough so
        // that we can't stop. Do one tick of acceleration, one tick of
        // deacceleration at that same rate. 
        // Want (1/2)(a)(dt^2) + (a dt)dt - (1/2)(a)(dt^2) = want_dist
        return want_dist / (cfg.dt_s * cfg.dt_s)
      }
    } else {
      // Special case for distance of 0: avoid a NaN, just stop.
      return Physics.accel_to_stop(a.speed)
    }
  }

  // Don't wait for a filled queue, maybe.
  private def impatient_turn(old: Ticket, e: Edge) = {
    // Consider being impatient if we're the head of our queue, we've been
    // idling for a little while, and it's because our target is filled.
    val idle_time = 90.0  // TODO cfg
    if (!old.is_interruption && a.how_long_idle > idle_time && a.at.on == e &&
        !a.our_lead.isDefined && !old.turn.to.queue.slot_avail)
    {
      val turn = route.pick_turn(e, avoid = old.turn)
      if (turn != old.turn) {
        //Util.log(s"$a impatiently switching from ${old.turn} to $turn")
        // TODO hackish, but reset this so we don't keep switching
        // TODO really, how long have we been denied because we're blocked?
        a.idle_since = -1.0
        val ticket = new Ticket(a, turn)
        a.tickets += ticket
        a.tickets -= old
        e.to.intersection.change_turn(old, ticket)
      }
    }
  }
}

// This is a lazy sequence of edges/turns that tracks distances away from the
// original spot. This assumes no lane-changing: where the agent starts
// predicting is where they'll end up.
class LookaheadStep(
  // TODO dist_left_to_analyze, dist_so_far?
  val at: Traversable, val predict_dist: Double, val dist_ahead: Double,
  val this_dist: Double, route: Route
) {
  // Steps start at the beginning of 'at', except for the 'first' lookahead
  // step. this_dist encodes that case. But dist_ahead is a way of measuring
  // how far the agent really is right now from something in the future.
  // predict_dist = how far ahead we still have to look
  // TODO consider seeding dist_ahead with not 0 but this_dist, then lots of
  // stuff may get simpler.
  // dist_ahead = how far have we looked ahead so far
  // at = where do we end up
  // this_dist = how much distance from 'at' we'll consider. it would just be
  // length, except for the very first step of a lookahead, since the agent
  // doesnt start at the beginning of the step.
  override def toString = "Lookahead to %s with %.2f m left".format(at, predict_dist)

  // TODO iterator syntax

  // TODO this and next_at, maybe move them out of this class
  // TODO the way this gets used is a bit redundant
  def is_last_step = at match {
    case e: Edge => route.done(e)
    case _ => false
  }

  lazy val next_at = at match {
    case e: Edge => route.pick_turn(e)
    case t: Turn => t.to
  }

  lazy val next_step: Option[LookaheadStep] =
    if (predict_dist - this_dist <= 0.0 || is_last_step)
      None
    else
      Some(new LookaheadStep(
        next_at, predict_dist - this_dist, dist_ahead + this_dist,
        next_at.length, route
      ))
}


// TODO this would be the coolest thing ever... driving game!
//class HumanControlBehavior(a: Agent) extends Behavior(a) {
//}

abstract class Action
final case class Act_Set_Accel(new_accel: Double) extends Action
final case class Act_Done_With_Route() extends Action
