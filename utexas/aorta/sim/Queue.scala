// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.sim

// I'd love to use scala's treemap, but it doesnt support higher/lowerkey.
import java.util.TreeMap
import scala.collection.JavaConversions.collectionAsScalaIterable

import utexas.aorta.map.{Edge, Traversable}

import utexas.aorta.{Util, cfg}

// TODO introduce a notion of dimension
// TODO logs -> asserts once things work right

// Reason about collisions on edges and within individual turns.
class Queue(t: Traversable) {
  private def wrap_option(entry: java.util.Map.Entry[Double, Agent]) =
    if (entry != null)
      Some(entry.getValue)
    else
      None

  // Descending by distance: the front of traversable has the greatest distance.
  val agents = new java.util.TreeMap[Double, Agent]()
  var last_tick = -1.0              // last observed
  // to verify no collisions occurred in a step
  var prev_agents: Set[Agent] = Set()

  def head = wrap_option(agents.firstEntry)
  def last = wrap_option(agents.lastEntry)

  // Called lazily.
  def start_step() = {
    if (last_tick != Agent.sim.tick) {
      prev_agents = agents.values.toSet
      last_tick = Agent.sim.tick
    }
  }
  
  // Check for collisions by detecting abnormal changes in ordering.
  def end_step(): Unit = {
    // TODO this is inefficient.
    // TODO if an agent ever looped around to the same edge again in one step,
    // this breaks badly.
    // TODO likewise, problems if an agent quickly lane-changed out, passed
    // someone, then back in.

    // Everything's fine.
    if (agents.isEmpty) {
      return
    }

    // TODO more efficiently?
    val alist = agents.values.toList
    // TODO check keys match value.at.dist

    // Since we allow lane-changing, some funny things could happen. So first
    // just check that the order of the distances matches the order of the
    // queue.
    if (!alist.zip(alist.tail).forall(pair => pair._1.at.dist > pair._2.at.dist)) {
      throw new Exception(
        s"Agents out of order on $t: " +
        alist.map(a => "%d at %.2f".format(a.id, a.at.dist))
      )
    }

    // Make sure nobody's crowding anybody else.
    for ((a1, a2) <- alist.zip(alist.tail)) {
      if (a1.at.dist < a2.at.dist + cfg.follow_dist) {
        throw new Exception(
          s"$a2 too close to $a1 on $t (" + (a1.at.dist - a2.at.dist) + ")"
        )
      }
    }

    // Now we just want to make sure that all of the agents here last tick are
    // in the same order. If some left, that's fine.
    val old_crowd = alist.filter(a => prev_agents.contains(a))

    if (old_crowd.size > 1) {
      // Since we know the ordering of the distances matches the ordering of the
      // queue from the first check, it suffices to check the ordering of the
      // distances in this list.
      if (!old_crowd.zip(old_crowd.tail).forall(pair => pair._1.at.dist > pair._2.at.dist)) {
        throw new Exception(s"Agents swapped positions on $t")
      }
    }
  }

  def enter(a: Agent, dist: Double): Position = {
    // Just find our spot.

    start_step  // lazily, if needed

    // Use -dist to make highest dist first and avoid comparator junk.
    agents.put(-dist, a)

    // If we're not entering at the end of the queue, something _could_ be odd,
    // so check it.
    if (closest_behind(dist).isDefined) {
      Agent.sim.active_queues += this
    }

    return Position(t, dist)
  }

  def exit(a: Agent, old_dist: Double) = {
    start_step  // lazily, if needed

    // We should leave from the front of the queue generally, unless
    // lane-changing
    if (agents.firstEntry.getValue != a) {
      Agent.sim.active_queues += this
    }

    agents.remove(-old_dist)
  }

  def move(a: Agent, new_dist: Double, old_dist: Double): Position = {
    // TODO more efficiently?
    exit(a, old_dist)
    return enter(a, new_dist)
  }

  def ahead_of(a: Agent) = closest_ahead(a.at.dist)
  def closest_behind(dist: Double) = wrap_option(agents.higherEntry(-dist))
  def closest_ahead(dist: Double) = wrap_option(agents.lowerEntry(-dist))

  // Geometric logic for spawning.

  // TODO this gets a bit more conservative when cars have different accelerations.
  // This is hinged on the fact that lookahead works. Agents can't enter e
  // faster than its speed limit, so we have to reason about how far they could
  // possibly go.
  def worst_entry_dist(): Double = {
    val lim = t match {
      case e: Edge => e.road.speed_limit
      case _       => throw new Exception("Only valid for edges, not turns!")
    }
    val accel = cfg.max_accel
    // TODO share this formula with Agent by util or something
    val stopping_dist = Util.dist_at_constant_accel(-accel, lim / accel, lim)
    return (lim * cfg.dt_s) + stopping_dist
  }
  
  // TODO Starting on highways seems weird, but allow it for now
  // TODO justify this better, or cite the paper.
  def ok_to_spawn = t.length >= worst_entry_dist + cfg.end_threshold + (2 * cfg.follow_dist)

  def ok_to_lanechange =
    (t.length >= cfg.lanechange_dist + cfg.end_threshold) &&
    // this second constraint can be removed once lookbehind is implemented
    (t.length >= worst_entry_dist + cfg.follow_dist)
  
  // TODO geometric argument
  // TODO sometimes the max arg is < the min arg. :)
  def safe_spawn_dist = Util.rand_double(
    worst_entry_dist + cfg.follow_dist, t.length - cfg.end_threshold
  )

  // The real-time spawning magic is really quite simple if worst_entry_dist and
  // lookahead work.
  def can_spawn_now(dist: Double): Boolean = {
    var safe = true
    // Find the first agent that makes us conclude there's a problem or we're
    // truly safe. This closure yields true when it wants to short-circuit.
    agents.descendingMap.values.find(a => {
      if (dist > a.at.dist) {
        val bad_dist = cfg.follow_dist + a.stopping_distance(a.max_next_speed)
        if (dist - a.at.dist <= bad_dist) {
          safe = false
          true
        } else {
          // keep looking
          false
        }
      } else {
        // don't spawn too close behind somebody
        if (a.at.dist - dist <= cfg.follow_dist) {
          safe = false
        } else {
          safe = true
        }
        true
      }
    })
    return safe
  }
}
