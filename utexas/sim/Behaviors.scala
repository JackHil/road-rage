package utexas.sim

import utexas.map.{Edge, Turn, Traversable}
import utexas.{Util, cfg}

abstract class Behavior(a: Agent) {
  val graph = a.graph

  // asked every tick after everybody has moved
  def choose_action(): Action
  // only queried when the agent reaches a vertex
  def choose_turn(e: Edge): Turn
  // every time the agent moves to a new traversable
  def transition(from: Traversable, to: Traversable)
  // just for debugging
  def dump_info()
}

// Never speeds up from rest, so effectively never does anything
class IdleBehavior(a: Agent) extends Behavior(a) {
  override def choose_action() = Act_Set_Accel(0)

  override def choose_turn(e: Edge) = e.next_turns.head

  override def transition(from: Traversable, to: Traversable) = {}   // mmkay

  override def dump_info() = {
    Util.log("Idle behavior")
  }
}

// the actual mechanics of the route are left up to something else, though
class RouteFollowingBehavior(a: Agent, route: Route) extends Behavior(a) {
  // This is set the first time we choose to begin stopping, and it helps since
  // worst-case analysis says we won't toe the line, but we still want to invoke
  // the same math.
  var keep_stopping = false

  override def choose_action(): Action = (a.at, route.next_step) match {
    case (Position(e1: Edge, _), Some(e2: Edge)) => {
      // TODO choose the right time
      return Act_Lane_Change(e2)
    }
    case _ => max_safe_accel
  }

  override def choose_turn(e: Edge): Turn = route.next_step match {
    case Some(t: Turn) => t
    case _             => throw new Exception("Asking " + a + " to choose turn now?!")
  }

  override def transition(from: Traversable, to: Traversable) = {
    route.transition(from, to)
    keep_stopping = false   // reset
  }

  override def dump_info() = {
    Util.log("Route-following behavior")
    // Just show a few steps ahead
    Util.log_push
    for (i <- 0 until 5) {
      Util.log("Step: " + route.lookahead_step(i))
    }
    Util.log_pop
  }

  // Just a struct to encode all of this info
  class LookaheadStep(
    val lookahead_left: Double, val looked_ahead_so_far: Double,
    val at: Traversable, val dist_left: Double, val steps_ahead: Int
  ) {}

  def lookahead(predict_dist: Double = a.max_lookahead_dist,
                start_at: Traversable = a.at.on,
                first_dist_left: Double = a.at.dist_left,
                start_steps_ahead: Int = 0
               ): List[LookaheadStep] =
  {
    var steps: List[LookaheadStep] = Nil

    var lookahead_left = predict_dist
    var looked_ahead_so_far = 0.0   // how far from a.at to beginning of step
    var at = start_at
    var dist_left = first_dist_left
    var steps_ahead = start_steps_ahead

    while (lookahead_left > 0.0) {
      steps :+= new LookaheadStep(
        lookahead_left, looked_ahead_so_far, at, dist_left, steps_ahead
      )

      lookahead_left -= dist_left
      if (!route.lookahead_step(steps_ahead).isDefined) {
        // done with route. stop considering possibilities immediately.
        lookahead_left = -1
      } else {
        looked_ahead_so_far += dist_left
        // order matters... steps_ahead will be what follows this new 'step'
        at = route.lookahead_step(steps_ahead).get
        steps_ahead += 1
        // For all but the first step, we have all of its distance left.
        dist_left = at.length
      }
    }

    return steps
  }

  // true if it could happen
  def check_for_gridlock(step: LookaheadStep): Boolean = {
    // Look ahead from the start of the turn until follow_dist
    // after the end of it too see if anybody's there.
    val cautious_turn = route.lookahead_step(step.steps_ahead) match {
      case Some(t: Turn) => t
      case _       => throw new Exception("not a turn next?")
    }
    val cautious_edge = cautious_turn.to

    val check_steps = lookahead(
      // TODO + 0.5 as an epsilon...
      predict_dist      = cautious_turn.length + cfg.follow_dist + 0.5,
      start_at          = cautious_turn,
      first_dist_left   = cautious_turn.length,
      start_steps_ahead = step.steps_ahead + 1  // TODO off by 1?
    )

    // If any of these have an agent, see where they are...
    return (check_steps.find(step => {
      val worry_about = Agent.sim.queues(step.at).last
      worry_about match {
        case Some(check_me) => {
          // the dist they are "away from start of lookahead" will
          // be from the start of the turn... subtract that turn's
          // length; that gives us how far away they are from the
          // end of the turn. make sure THAT'S a nice comfy value.
          val dist_from_end_of_turn = step.looked_ahead_so_far + check_me.at.dist - cautious_turn.length
          // TODO some epsilon?
          dist_from_end_of_turn <= cfg.follow_dist  // they too close?
        }
        case None => false  // nobody's there, fine
      }
    }).isDefined)
  }

  // Returns Act_Set_Accel almost always.
  def max_safe_accel(): Action = {
    // Since we can't react instantly, we have to consider the worst-case of the
    // next tick, which happens when we speed up as much as possible this tick.

    // the output.
    var stop_how_far_away: Option[Double] = None
    var stop_at: Option[Traversable] = None
    var stopping_for_destination = false
    var follow_agent: Option[Agent] = None
    var follow_agent_how_far_away = 0.0   // gets set when follow_agent does.
    var min_speed_limit = Double.MaxValue

    // Stop caring once we know we have to stop for some intersection AND stay
    // behind some agent. Need both, since the agent we're following may not
    // need to stop, but we do.
    for (step <- lookahead() if (!stop_how_far_away.isDefined || !follow_agent.isDefined)) {
      // 1) Agent
      // Worry either about the one we're following, or the one at the end of
      // the queue right now. It's the intersection's job to worry about letting
      // another wind up on our lane at the wrong time.
      if (!follow_agent.isDefined) {
        follow_agent = if (a.at.on == step.at)
                         a.cur_queue.ahead_of(a)
                       else
                         Agent.sim.queues(step.at).last
        follow_agent_how_far_away = follow_agent match {
          // A bit of a special case, that looked_ahead_so_far doesn't cover well.
          case Some(f) if f.at.on == a.at.on => f.at.dist - a.at.dist
          case Some(f) => step.looked_ahead_so_far + f.at.dist
          case _       => 0.0
        }
      }

      // Do agent first to avoid doing some extra lookahead to avoid a form of
      // gridlock.

      // 2) Stopping at the end
      if (!stop_how_far_away.isDefined) {
        // physically stop somewhat far back from the intersection.
        val should_stop = keep_stopping || (step.lookahead_left >= step.dist_left - cfg.end_threshold)
        val how_far_away = step.looked_ahead_so_far + step.dist_left

        val stop_at_end: Boolean = should_stop && (step.at match {
          // Don't stop at the end of a turn
          case t: Turn => false
          // Stop if we're arriving at destination
          case e if !route.lookahead_step(step.steps_ahead).isDefined => {
            stopping_for_destination = true
            true
          }
          // Otherwise, ask the intersection
          case e: Edge => {
            val i = Agent.sim.intersections(e.to)
            a.upcoming_intersections += i   // remember we've registered here
            val next_turn = route.lookahead_step(step.steps_ahead) match {
              case Some(t: Turn) => t
              case _ => throw new Exception("next_turn() called at wrong time")
            }
            val stop_for_policy = !i.can_go(a, next_turn, how_far_away)

            // Stop for somebody if they could cause us problems
            val stop_for_gridlock = if (stop_for_policy)
                                      false // don't bother computing, OR
                                    else (follow_agent match
            {
              // If we've already found somebody we're following, they must be
              // somewhere on the edge leading up to this intersection, so they
              // haven't started their turn yet. So there's a danger they could
              // take the same turn we're doing and cause us to not finish our
              // turn cleanly, possibly leading to gridlock.
              // In other words, it doesn't matter how far away they are -- they
              // haven't started their turn yet, so wait for them to completely
              // finish first.
              case Some(a) => true
              case _ => check_for_gridlock(step)
            })

            stop_for_policy || stop_for_gridlock
          }
        })
        if (stop_at_end) {
          keep_stopping = true
          stop_how_far_away = Some(how_far_away)
          stop_at = Some(step.at)
        }
      }

      // 3) Speed limit
      step.at match {
        case e: Edge => { min_speed_limit = math.min(min_speed_limit, e.road.speed_limit) }
        case t: Turn => None
      }
    }

    if (stopping_for_destination && stop_how_far_away.get <= cfg.end_threshold && a.speed == 0.0) {
      // We can't get any closer to our actual destination. Terminate.
      return Act_Done_With_Route()
    } else {
      // So, three possible constraints.
      val a1 = stop_how_far_away match {
        case Some(dist) => {
          // Stop 'end_threshold' short of where we should when we can, but when
          // our destination is an edge, compromise and stop anywhere along it
          // we can. This handles a few stalemate cases with sequences of short
          // edges and possibly out-of-sync intersection policies.
          val last_stop = stop_at.get
          val go_this_dist = last_stop match {
            // creep forward to halfway along the shorty edge.
            case e: Edge if dist <= cfg.end_threshold => dist - (last_stop.length / 2.0)
            // stop back appropriately.
            case _                                    => dist - cfg.end_threshold
          }
          accel_to_end(go_this_dist)
        }
        case _          => Double.MaxValue
      }
      val a2 = follow_agent match {
        case Some(f) => accel_to_follow(f, follow_agent_how_far_away)
        case _       => Double.MaxValue
      }
      val a3 = a.accel_to_achieve(min_speed_limit)

      if (a.id == 1472) {
        Util.log("  ~ %.1f, %.1f, %.1f".format(a1, a2, a3))
      }

      val conservative_accel = math.min(a1, math.min(a2, a3))

      // let the other intersections that are letting us go know that we
      // won't be going just yet,
      // IF we are not going to make a move this tick.

      // No longer a problem, but detect a cause for gridlock here: when speed
      // and conservative_accel are nil, especially when we're in a turn.

      // As the very last step, clamp based on our physical capabilities.
      return Act_Set_Accel(if (conservative_accel > 0)
                             math.min(conservative_accel, a.max_accel)
                           else
                             math.max(conservative_accel, -a.max_accel))
    }
  }

  private def accel_to_follow(follow: Agent, dist_from_them_now: Double): Double = {
    // Maintain our stopping distance away from this guy, plus don't scrunch
    // together too closely...

    // Again, reason about the worst-case: we speed up as much as possible, they
    // slam on their brakes.
    val us_worst_stop_dist = a.stopping_distance(a.max_next_speed)
    val most_we_could_go = a.max_next_dist
    val least_they_could_go = follow.min_next_dist

    // TODO this optimizes for next tick, so we're playing it really
    // conservative here... will that make us fluctuate more?
    val projected_dist_from_them = dist_from_them_now - most_we_could_go + least_they_could_go

    // don't ride bumper-to-bumper
    val desired_dist_btwn = us_worst_stop_dist + cfg.follow_dist

    // Positive = speed up, zero = go their speed, negative = slow down
    val delta_dist = projected_dist_from_them - desired_dist_btwn

    // Try to cover whatever the distance is, and cap off our values.
    val accel = a.accel_to_cover(delta_dist)

    // TODO its a bit scary that this ever happens? does that mean we're too
    // close..?
    // Make sure we don't deaccelerate past 0 either.
    var accel_to_stop = a.accel_to_achieve(0)

    // TODO dumb epsilon bug. fix this better.
    val stop_speed = a.speed + (accel_to_stop * cfg.dt_s)
    if (stop_speed < 0) {
      accel_to_stop += 0.1
    }

    return math.max(accel_to_stop, accel)
  }

  // This is based on Piyush's proof.
  // how_far_away already includes end_threshold, if appropriate.
  private def accel_to_end(how_far_away: Double): Double = {
    if (a.id == 1472) {
      Util.log(" -- cover " + how_far_away + " from speed " + a.speed)
    }

    if (how_far_away > 0) {
      // See if it's even possible, or if we're doomed.
      // In other words, if we slam on our breaks now, how far do we travel?
      val stop_dist_now = Util.dist_at_constant_accel(-a.max_accel, cfg.dt_s, a.speed)
      if (stop_dist_now >= how_far_away) {
        Util.log("!!!!!!! " + a.id + " is doomed: " + (how_far_away - stop_dist_now))
      }
    }

    // What accel do we feed dist_at_constant_accel that makes it be equal to
    // how_far_away?
    // dist = (1/2)a(t^2) + (v_i)t
    val direct_accel = 2.0 * (math.max(0.0, how_far_away) - (cfg.dt_s * a.speed)) / (cfg.dt_s * cfg.dt_s)
    // make sure 1) this travels the correct amount
      //assert(Util.dist_at_constant_accel(direct_accel, cfg.dt_s, a.speed) <= math.max(0.0, how_far_away))
      val goal_dist = math.max(0.0, how_far_away)
      // this does: (v_i)t + (1/2)a(t^2)
      val would_dist = Util.dist_at_constant_accel(direct_accel, cfg.dt_s, a.speed)
      if (would_dist > goal_dist) {
        Util.log(would_dist + " exceeds " + goal_dist)
      }
    // make sure 2) this stops at speed 0
      //assert(a.speed + (direct_accel * cfg.dt_s) >= 0.0)
      val end_spd = a.speed + (direct_accel * cfg.dt_s)
      if (end_spd < 0.0) {
        Util.log("end speed " + end_spd)
        Util.log("       " + how_far_away + " dist")
        Util.log("       " + a.speed + " init speed")
        Util.log("       " + cfg.dt_s +  "dt")
        Util.log("       " + direct_accel + " was chosen accel")
      }


    // TODO and also make sure this is either within max accel, or if it's not,
    // we can keep stopping from there and not exceed.

    // a, b, c for a normal quadratic
    val q_a = 1 / a.max_accel
    val q_b = cfg.dt_s
    val q_c = (a.speed * cfg.dt_s) - (2 * how_far_away)
    val try_speed = (-q_b + math.sqrt((q_b * q_b) - (4 * q_a * q_c))) / (2 * q_a)

    // TODO why does this or NaN ever happen?
    /*if (desired_speed < 0) {
      Util.log("why neg speed?")
    } else if (desired_speed.isNaN) {
      // try seed 1327894373344 to make it happen, though. synthetic.
      Util.log("NaN speed... a=" + q_a + ", b=" + q_b + ", c=" + q_c)
    }*/

    // in the NaN case, just try to stop?
    val desired_speed = if (try_speed.isNaN)
                          0
                        else
                          math.max(0, try_speed)

    val needed_accel = a.accel_to_achieve(desired_speed)

    // TODO dumb epsilon bug again. fix this better.
    val stop_speed = a.speed + (needed_accel * cfg.dt_s)
    val choice_accel = if (stop_speed < 0)
                         needed_accel + 0.1
                       else
                         needed_accel

    if (a.id == 1472) {
      Util.log("  returning " + choice_accel + " but direct " + direct_accel)
    }

    return choice_accel
  }
}

// TODO this would be the coolest thing ever... driving game!
//class HumanControlBehavior(a: Agent) extends Behavior(a) {
//}

abstract class Action
final case class Act_Set_Accel(new_accel: Double) extends Action
final case class Act_Lane_Change(lane: Edge) extends Action
final case class Act_Done_With_Route() extends Action
