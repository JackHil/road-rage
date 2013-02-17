// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map.analysis

import scala.collection.mutable.{PriorityQueue, HashSet, ListBuffer}
import com.graphhopper.storage.{LevelGraphStorage, RAMDirectory}
import com.graphhopper.routing.ch.PrepareContractionHierarchies

import utexas.aorta.map.{Graph, Vertex, DirectedRoad}

import utexas.aorta.Util

abstract class Router(graph: Graph) {
  // Doesn't include 'from' as the first step
  def path(from: Vertex, to: Vertex): List[Vertex]

  // This is mainly useful for UI
  def path_as_roads(from: Vertex, to: Vertex): List[DirectedRoad] = {
    val p = path(from, to)
    return p.zip(p.tail).map(
      pair => pair._1.directed_roads.find(r => r.v2 == pair._2).get
    )
  }
}

// A convenient abstraction if we ever switch to pathfinding on
// edges/roads/others.
abstract class AbstractEdge {
  var id: Int // TODO var because fix_ids
  def length: Double
  // List and not Set means there could be multiple transitions, with possibly
  // different weights.
  def succs: Seq[(AbstractEdge, Double)]
  def preds: Seq[(AbstractEdge, Double)]
}

class DijkstraRouter(graph: Graph) extends Router(graph) {
  def costs_to(v: DirectedRoad) = dijkstras(
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

    // Edges in the open set don't have their final distance yet
    val open = new PriorityQueue[Step]()
    val done = new HashSet[AbstractEdge]()

    costs(source.id) = 0
    open.enqueue(new Step(source))

    while (open.nonEmpty) {
      val step = open.dequeue
      
      // Skip duplicate steps, since we chose option 3 for the problem below.
      if (!done.contains(step.edge)) {
        done += step.edge

        for ((next, transition_length) <- next(step.edge) if !done.contains(next)) {
          val dist = step.cost + transition_length + next.length
          if (dist < costs(next.id)) {
            // Relax!
            costs(next.id) = dist
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

  def path(from: Vertex, to: Vertex): List[Vertex] = {
    Util.assert_eq(usable, true)
    val path = algo.calcPath(from.id, to.id)
    Util.assert_eq(path.found, true)
    algo.clear

    val result = new ListBuffer[Vertex]()
    var iter = path.calcNodes.iterator
    while (iter.hasNext) {
      result += graph.vertices(iter.next)
    }
    return result.tail.toList
  }
}
