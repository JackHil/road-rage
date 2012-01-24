package utexas.sim

import utexas.map.{Edge, Coordinate, Turn, Traversable, Graph}
import utexas.{Util, cfg}

// TODO come up with a notion of dimension and movement capability. at first,
// just use radius bounded by lane widths?

class Agent(id: Int, val graph: Graph, start: Edge) {
  var at = enter(start, Agent.sim.queues(start).random_spawn)

  // We can only set a target acceleration, which we travel at for the entire
  // duration of timesteps.
  val max_accel = 2.7   // TODO cfg and based on vehicle type
  var speed: Double = 0.0   // meters/sec, I believe
  var target_accel: Double = 0  // m/s^2
  val behavior = new DangerousBehavior(this) // TODO who chooses this?

  override def toString = "Agent " + id

  // just tell the behavior
  def go_to(e: Edge) = {
    behavior.set_goal(e)
  }

  def step(dt_s: Double): Unit = {
    assert(dt_s <= cfg.max_dt)

    val start_on = at.on
    val old_dist = at.dist

    // Do physics to update current speed and figure out how far we've traveled in
    // this timestep.
    val new_dist = update_kinematics(dt_s)

    // Apply this distance. 
    var current_on = start_on
    var current_dist = old_dist + new_dist
    while (current_dist > current_on.length) {
      current_dist -= current_on.length
      // Are we finishing a turn or starting one?
      val next: Option[Traversable] = current_on match {
        case e: Edge => behavior.choose_turn(e)
        case t: Turn => Some(t.to)
      }

      // tell the intersection
      (current_on, next) match {
        case (e: Edge, Some(t: Turn)) => Agent.sim.intersections(t.vert).enter(this, t)
        case (t: Turn, Some(e: Edge)) => Agent.sim.intersections(t.vert).exit(this, t)
        case (e: Edge, None)          =>
      }

      next match {
        case Some(t) => {
          // this lets behaviors make sure their route is being followed
          behavior.transition(current_on, t)
          current_on = t
        }
        case None => {
          // Done. Disappear!
          exit(start_on)
          return
        }
      }
    }
    // so we finally end up somewhere...
    if (start_on == current_on) {
      at = move(start_on, current_dist)
    } else {
      exit(start_on)
      at = enter(current_on, current_dist)
    }
    // TODO deal with lane-changing
  }

  def react() = {
    behavior.choose_action match {
      case Act_Set_Accel(new_accel) => {
        assert(new_accel.abs <= max_accel)
        target_accel = new_accel
      }
      case Act_Lane_Change(lane)    => {
        // TODO ensure it's a valid request
        Util.log("TODO lanechange")
      }
    }
  }

  // returns distance traveled, updates speed. note unit of the argument.
  def update_kinematics(dt_sec: Double): Double = {
    // Simply travel at the target constant acceleration for the duration of the
    // timestep.
    val initial_speed = speed
    speed = initial_speed + (target_accel * dt_sec)
    val dist = Util.dist_at_constant_accel(target_accel, dt_sec, initial_speed)

    // It's the behavior's burden to set acceleration so that neither of these
    // cases happen
    assert(speed >= 0.0)
    assert(dist >= 0.0)

    return dist
  }

  // Delegate to the queues and intersections that simulation manages
  def enter(t: Traversable, dist: Double) = Agent.sim.queues(t).enter(this, dist)
  def exit(t: Traversable): Unit = Agent.sim.queues(t).exit(this)
  def move(t: Traversable, dist: Double)  = Agent.sim.queues(t).move(this, dist)

  def dump_info() = {
    Util.log("" + this)
    Util.log_push
    Util.log("At: " + at)
    Util.log("Speed: " + speed)
    Util.log("Next step's acceleration: " + target_accel)
    behavior.dump_info
    Util.log_pop
  }

  def cur_queue = Agent.sim.queues(at.on)

  // stopping time comes from v_f = v_0 + a*t
  // negative accel because we're slowing down.
  def stopping_distance = Util.dist_at_constant_accel(-max_accel, speed / max_accel, speed)
}

// the singleton just lets us get at the simulation to look up queues
object Agent {
  var sim: Simulation = null
}

////////////////////////////////////////////////////////////////////////////////

// VoidPosition used to exist too, but I couldn't work out when we would ever
// want it. If there's an agent waiting to enter the map, they don't need to
// exist yet.

case class Position(val on: Traversable, val dist: Double) {
  assert(dist >= 0)
  assert(dist <= on.length)

  def location = on.location(dist)
  def dist_left = on.length - dist
}
