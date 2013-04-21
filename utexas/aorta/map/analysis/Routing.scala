// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map.analysis

import scala.collection.mutable.{PriorityQueue, HashSet, ListBuffer, HashMap}
import com.graphhopper.storage.{LevelGraphStorage, RAMDirectory}
import com.graphhopper.routing.ch.PrepareContractionHierarchies

import utexas.aorta.map.{Graph, DirectedRoad}

import utexas.aorta.Util

abstract class Router(graph: Graph) {
  // Doesn't include 'from' as the first step
  def path(from: DirectedRoad, to: DirectedRoad): List[DirectedRoad]
}

// A convenient abstraction if we ever switch to pathfinding on
// edges/roads/others.
abstract class AbstractEdge {
  var id: Int // TODO var because fix_ids
  def cost: Double
  // List and not Set means there could be multiple transitions, with possibly
  // different weights.
  def succs: Seq[(AbstractEdge, Double)]
  def preds: Seq[(AbstractEdge, Double)]
}

class DijkstraRouter(graph: Graph) extends Router(graph) {
  def costs_to(r: DirectedRoad) = dijkstras(
    graph.directed_roads.size, r, (e: AbstractEdge) => e.preds
  )

  def path(from: DirectedRoad, to: DirectedRoad) =
    hillclimb(costs_to(to), from).tail.asInstanceOf[List[DirectedRoad]]

  // Precomputes a table of the cost from source to everything.
  def dijkstras(size: Int, source: AbstractEdge,
                next: AbstractEdge => Seq[(AbstractEdge, Double)]): Array[Double] =
  {
    val costs = Array.fill[Double](size)(Double.PositiveInfinity)

    // TODO needs tests!
    // TODO pass in a comparator to the queue instead of having a wrapper class
    class Step(val edge: AbstractEdge) extends Ordered[Step] {
      def cost = costs(edge.id)
      def compare(other: Step) = other.cost.compare(cost)
    }

    // Edges in the open set don't have their final cost yet
    val open = new PriorityQueue[Step]()
    val done = new HashSet[AbstractEdge]()

    costs(source.id) = 0
    open.enqueue(new Step(source))

    while (open.nonEmpty) {
      val step = open.dequeue
      
      // Skip duplicate steps, since we chose option 3 for the problem below.
      if (!done.contains(step.edge)) {
        done += step.edge

        for ((next, transition_cost) <- next(step.edge) if !done.contains(next)) {
          val cost = step.cost + transition_cost + next.cost
          if (cost < costs(next.id)) {
            // Relax!
            costs(next.id) = cost
            // TODO ideally, decrease-key
            // 1) get a PQ that uses a fibonacci heap
            // 2) remove, re-insert again
            // 3) just insert a dupe, then skip the duplicates when we get to them
            // Opting for 3, for now.
            open.enqueue(new Step(next))
          }
        }
      }
    }
    return costs
  }

  // Starts at source, hillclimbs to lower costs, and returns the path to 0.
  def hillclimb(costs: Array[Double], start: AbstractEdge): List[AbstractEdge] =
    costs(start.id) match {
      case 0 => start :: Nil
      case c => start :: hillclimb(
        costs, start.succs.minBy(t => costs(t._1.id))._1
      )
    }
}

class CHRouter(graph: Graph) extends Router(graph) {
  private val gh = new LevelGraphStorage(
    new RAMDirectory(s"maps/route_${graph.name}", true)
  )
  var usable = gh.loadExisting
  private val algo = new PrepareContractionHierarchies().graph(gh).createAlgo

  def path(from: DirectedRoad, to: DirectedRoad): List[DirectedRoad] = {
    // GraphHopper can't handle loops. For now, empty path; freebie.
    // TODO force a loop by starting on a road directly after 'from'
    if (from == to) {
      return Nil
    }

    Util.assert_eq(usable, true)
    val path = algo.calcPath(from.id, to.id)
    algo.clear
    Util.assert_eq(path.found, true)

    val result = new ListBuffer[DirectedRoad]()
    var iter = path.calcNodes.iterator
    while (iter.hasNext) {
      result += graph.directed_roads(iter.next)
    }
    return result.tail.toList
  }
}

// Run sparingly -- it's A* that penalizes going through roads that're congested
// right now
class CongestionRouter(graph: Graph) extends Router(graph) {
  // TODO clean this up >_<
  def path(from: DirectedRoad, to: DirectedRoad): List[DirectedRoad] = {
    if (from == to) {
      return Nil
    }

    val goal_pt = to.end_pt
    def calc_heuristic(state: DirectedRoad) =
      (0.0, state.end_pt.dist_to(goal_pt))

    def add_cost(a: (Double, Double), b: (Double, Double)) =
      (a._1 + b._1, a._2 + b._2)

    // Stitch together our path
    val backrefs = new HashMap[DirectedRoad, DirectedRoad]()
    // We're finished with these
    val visited = new HashSet[DirectedRoad]()
    // Best cost so far
    val costs = new HashMap[DirectedRoad, (Double, Double)]()

    case class Step(state: DirectedRoad) {
      lazy val heuristic = calc_heuristic(state)
      def cost = add_cost(costs(state), heuristic)
    }
    val ordering = Ordering[(Double, Double)].on((step: Step) => step.cost).reverse
    val ordering_tuple = Ordering[(Double, Double)].on((pair: (Double, Double)) => pair)

    // Priority queue grabs highest priority first, so reverse to get lowest
    // cost first.
    val open = new PriorityQueue[Step]()(ordering)
    // Used to see if we've already added a road to the queue
    val open_members = new HashSet[DirectedRoad]()

    costs(from) = (0, 0)
    open.enqueue(Step(from))
    open_members += from
    backrefs(from) = null

    while (open.nonEmpty) {
      val current = open.dequeue()
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
        return path.tail
      } else {
        for ((next_state_raw, transition_cost) <- current.state.succs) {
          val next_state = next_state_raw.asInstanceOf[DirectedRoad]
          val next_time_cost = transition_cost + next_state.cost
          val next_congestion_cost =
            if (next_state.is_congested)
              1.0
            else
              0.0
          val tentative_cost = add_cost(
            costs(current.state), (next_congestion_cost, next_time_cost)
          )
          if (!visited.contains(next_state) && (!open_members.contains(next_state) || ordering_tuple.lt(tentative_cost, costs(next_state)))) {
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
