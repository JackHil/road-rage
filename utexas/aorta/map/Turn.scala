// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map

import scala.collection.mutable.HashSet

import utexas.aorta.common.{Common, StateWriter, StateReader}

// This constructor eschews geometry, taking a length and two points for
// conflicts.
// TODO from and to ID are var because we fiddle with IDs during Pass 3
// construction.
class Turn(val id: Int, var from_id: Int, var to_id: Int)
  extends Traversable with Ordered[Turn]
{
  //////////////////////////////////////////////////////////////////////////////
  // State

  // TODO var because points change
  var conflict_line: Line = null

  //////////////////////////////////////////////////////////////////////////////
  // Deterministic state

  var from: Edge = null
  var to: Edge = null

  //////////////////////////////////////////////////////////////////////////////
  // Meta

  override def serialize(w: StateWriter) {
    w.int(id)
    w.int(from_id)
    w.int(to_id)
    // Don't serialize our conflict line, since it follows from our state.
    // TODO if we ever trim it up, then watch out.
  }

  def setup(g: GraphLike) {
    from = g.get_e(from_id)
    to = g.get_e(to_id)
    // When this is called during unserializing, this should've already
    // happened.
    conflict_line = new Line(from.end_pt, to.start_pt)
    set_lines(Array(conflict_line))
  }

  //////////////////////////////////////////////////////////////////////////////
  // Actions

  override def set_lines(l: Array[Line]) = {
    // Ignore the input and just recompute our conflict line.
    conflict_line = new Line(from.end_pt, to.start_pt)
    super.set_lines(Array(conflict_line))
  }

  //////////////////////////////////////////////////////////////////////////////
  // Queries

  override def compare(other: Turn) = id.compare(other.id)

  override def toString = "turn[" + id + "](" + from + ", " + to + ")"
  // Short form is nice.
  //override def toString = "Turn(" + from.id + ", " + to.id + ")"

  def leads_to = List(to)
  def speed_limit = to.speed_limit

  def involves_road(r: Road) = (from.road == r || to.road == r)
  def other_road(r: Road) = if (from.road == r) to.road else from.road

  def vert = from.to

  def conflicts_with(t: Turn) =
    (from != t.from) && (to == t.to || conflict_line.segment_intersection(t.conflict_line).isDefined)

  // TODO more efficiently?
  def conflicts = vert.turns.filter(conflicts_with).toSet
}

object Turn {
  def unserialize(r: StateReader) = new Turn(r.int, r.int, r.int)
}
