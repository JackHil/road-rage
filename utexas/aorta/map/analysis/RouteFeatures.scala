// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map.analysis

import scala.collection.mutable.{PriorityQueue, HashSet, ListBuffer, HashMap}

import utexas.aorta.map.{Graph, DirectedRoad, Coordinate}
import utexas.aorta.sim.{IntersectionType, Scenario, RouterType}

import utexas.aorta.common.{Util, Common, MathVector}

// Encodes all factors describing the quality of a path
case class RouteFeatures(
  total_length: Double,           // sum of path length
  total_freeflow_time: Double,    // sum of time to cross each road at the speed limit
  congested_road_count: Double,   // number of congested roads
  stop_sign_count: Double,        // number of intersections crossed
  signal_count: Double,
  reservation_count: Double,
  queued_turn_count: Double,      // sum of queued turns at each intersection
  total_avg_waiting_time: Double, // sum of average waiting time of turns at each intersection
  road_demand: Double,            // number of agents who want to use this road sometime
  intersection_demand: Double,    // likewise for intersections
  agents_enroute: Double          // number of agents on the path right now

  // TODO historical avg/max of road congestion, and more than a binary is/is not
  // TODO account for intersection type?
  // TODO historical avg/max. and count this carefully!
) extends MathVector[RouteFeatures](Array(
  total_length, total_freeflow_time, congested_road_count, stop_sign_count, signal_count,
  reservation_count, queued_turn_count, total_avg_waiting_time, road_demand, intersection_demand,
  agents_enroute
)) {
  override def produce(v: Array[Double]) =
    RouteFeatures(v(0), v(1), v(2), v(3), v(4), v(5), v(6), v(7), v(8), v(9), v(10))
}

object RouteFeatures {
  // Some presets
  val BLANK = RouteFeatures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  val JUST_FREEFLOW_TIME = BLANK.copy(total_freeflow_time = 1)

  def for_step(step: DirectedRoad, demand: Demand): RouteFeatures = {
    def one_if(matches: IntersectionType.Value) =
      Util.bool2binary(step.to.intersection.policy.policy_type == matches)
    return RouteFeatures(
      total_length = step.length,
      total_freeflow_time = step.cost(0.0),
      congested_road_count = if (step.is_congested) 1 else 0,
      stop_sign_count = one_if(IntersectionType.StopSign),
      signal_count = one_if(IntersectionType.Signal),
      reservation_count = one_if(IntersectionType.Reservation),
      queued_turn_count = step.to.intersection.policy.queued_count,
      total_avg_waiting_time = step.to.intersection.average_waiting_time,
      road_demand = demand.directed_roads(step.id.int).toDouble,
      intersection_demand = demand.intersections(step.to.id.int).toDouble,
      agents_enroute = step.edges.map(_.queue.agents.size).sum
    )
  }
}

// Magically knows where everybody wants to go ahead of time. Think of this as representing a
// historical average, though.
case class Demand(directed_roads: Array[Integer], intersections: Array[Integer])

object Demand {
  def blank_for(scenario: Scenario, graph: Graph) =
    Demand(Array.fill(graph.directed_roads.size)(0), Array.fill(graph.vertices.size)(0))

  def demand_for(scenario: Scenario, graph: Graph): Demand = {
    val demand = blank_for(scenario, graph)
    /*for (a <- scenario.agents) {
      val from = graph.edges(a.start_edge.int).directed_road
      val to = graph.edges(a.route.goal.int).directed_road
      for (step <- graph.router.path(from, to, 0)) {
        demand.directed_roads(step.id.int) += 1
        demand.intersections(step.to.id.int) += 1
      }
    }*/
    return demand
  }
}

// A* is a misnomer; there's no heuristic right now.
class AstarRouter(graph: Graph, val weights: RouteFeatures, demand: Demand) extends Router(graph) {
  override def router_type = RouterType.TMP
  override def path(from: DirectedRoad, to: DirectedRoad, time: Double) = scored_path(from, to)._1

  // Return the weight of the final path too
  def scored_path(from: DirectedRoad, to: DirectedRoad): (List[DirectedRoad], RouteFeatures) = {
    if (from == to) {
      return (Nil, RouteFeatures.BLANK)
    }

    Common.sim.astar_since_last_time += 1

    // Stitch together our path
    val backrefs = new HashMap[DirectedRoad, DirectedRoad]()
    // We're finished with these
    val visited = new HashSet[DirectedRoad]()
    // Best cost so far
    val costs = new HashMap[DirectedRoad, RouteFeatures]()
    // Used to see if we've already added a road to the queue
    val open_members = new HashSet[DirectedRoad]()

    case class Step(state: DirectedRoad) {
      // No heuristics for now
      def cost = costs(state).dot(weights)
    }
    val ordering = Ordering[Double].on((step: Step) => step.cost).reverse
    // Priority queue grabs highest priority first, so reverse to get lowest
    // cost first.
    val open = new PriorityQueue[Step]()(ordering)

    costs(from) = RouteFeatures.BLANK
    open.enqueue(Step(from))
    open_members += from
    backrefs(from) = null

    while (open.nonEmpty) {
      val current = open.dequeue()
      //println(s"- examining ${current.state} with cost ${current.cost} (${costs(current.state)})")
      visited += current.state
      open_members -= current.state

      if (current.state == to) {
        // Reconstruct the path
        var path: List[DirectedRoad] = Nil
        var pointer: Option[DirectedRoad] = Some(current.state)
        while (pointer.isDefined && pointer.get != null) {
          path = pointer.get :: path
          // Clean as we go to break loops
          pointer = backrefs.remove(pointer.get)
        }
        // Exclude 'from'
        return (path.tail, costs(current.state))
      } else {
        for ((next_state_raw, _) <- current.state.succs) {
          val next_state = next_state_raw.asInstanceOf[DirectedRoad]
          val tentative_cost = costs(current.state) + RouteFeatures.for_step(next_state, demand)
          if (!visited.contains(next_state) && (!open_members.contains(next_state) || tentative_cost.dot(weights) < costs(next_state).dot(weights))) {
            backrefs(next_state) = current.state
            costs(next_state) = tentative_cost
            // TODO if they're in open_members, modify weight in the queue? or
            // new step will clobber it. fine.
            open.enqueue(Step(next_state))
            open_members += next_state
          }
        }
      }
    }

    // We didn't find the way?! The graph is connected!
    throw new Exception("Couldn't A* from " + from + " to " + to)
  }
}
