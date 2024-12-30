package selector;

import java.awt.Point;
import java.util.ListIterator;

/**
 * Models a selection tool that connects each added point with a straight line.
 */
public class PointToPointSelectionModel extends SelectionModel {

    enum PointToPointState implements SelectionState {
        /**
         * No selection is currently in progress (no starting point has been selected).
         */
        NO_SELECTION,

        /**
         * Currently assembling a selection.  A starting point has been selected, and the selection
         * path may contain a sequence of segments, which can be appended to by adding points.
         */
        SELECTING,

        /**
         * The selection path represents a closed selection that start and ends at the same point.
         * Points may be moved, but no additional points may be added.  The selected region of the
         * image may be extracted and saved from this state.
         */
        SELECTED;

        @Override
        public boolean isEmpty() {
            return this == NO_SELECTION;
        }

        @Override
        public boolean isFinished() {
            return this == SELECTED;
        }

        @Override
        public boolean canUndo() {
            return this == SELECTED || this == SELECTING;
        }

        @Override
        public boolean canAddPoint() {
            return this == NO_SELECTION || this == SELECTING;
        }

        @Override
        public boolean canFinish() {
            return this == SELECTING;
        }

        @Override
        public boolean canEdit() {
            return this == SELECTED;
        }

        @Override
        public boolean isProcessing() {
            return false;
        }
    }

    /**
     * The current state of this selection model.
     */
    private PointToPointState state;

    /**
     * Create a model instance with no selection and no image.  If `notifyOnEdt` is true, property
     * change listeners will be notified on Swing's Event Dispatch thread, regardless of which
     * thread the event was fired from.
     */
    public PointToPointSelectionModel(boolean notifyOnEdt) {
        super(notifyOnEdt);
        state = PointToPointState.NO_SELECTION;
    }

    /**
     * Create a model instance with the same image and event notification policy as `copy`, and
     * attempt to preserve `copy`'s selection if it can be represented without violating the
     * invariants of this class.
     */
    public PointToPointSelectionModel(SelectionModel copy) {
        super(copy);
        if (copy instanceof PointToPointSelectionModel) {
            state = ((PointToPointSelectionModel) copy).state;
        } else {
            if (copy.state().isEmpty()) {
                assert segments.isEmpty() && controlPoints.isEmpty();
                state = PointToPointState.NO_SELECTION;
            } else if (!copy.state().isFinished() && controlPoints.size() == segments.size() + 1) {
                // Assumes segments start and end at control points
                state = PointToPointState.SELECTING;
            } else if (copy.state().isFinished() && controlPoints.size() == segments.size()) {
                // Assumes segments start and end at control points
                state = PointToPointState.SELECTED;
            } else {
                reset();
            }
        }
    }

    @Override
    public SelectionState state() {
        return state;
    }

    /**
     * Change our selection state to `newState` (internal operation).  This should only be used to
     * perform valid state transitions.  Notifies listeners that the "state" property has changed.
     */
    private void setState(PointToPointState newState) {
        PointToPointState oldState = state;
        state = newState;
        propSupport.firePropertyChange("state", oldState, newState);
    }

    /**
     * Return a straight line segment from our last point to `p`.
     */
    @Override
    public PolyLine liveWire(Point p) {
        assert p != null;

        return new PolyLine(lastPoint(), p);
    }

    /**
     * Add `p` as the next control point of our selection, extending our selection with a straight
     * line segment from the end of the current selection path to `p`.
     */
    @Override
    protected void appendToSelection(Point p) {
        assert p != null;

        if (segments.isEmpty()) {
            setState(PointToPointState.SELECTING);
        }
        segments.add(liveWire(p));
        controlPoints.add(new Point(p));
    }

    /**
     * Move the control point with index `index` to `newPos`.  The segment that previously
     * terminated at the point should be replaced with a straight line connecting the previous point
     * to `newPos`, and the segment that previously started from the point should be replaced with a
     * straight line connecting `newPos` to the next point (where "next" and "previous" wrap around
     * as necessary). Notify listeners that the "selection" property has changed.
     */
    @Override
    public void movePoint(int index, Point newPos) {
        assert newPos != null;

        // Confirm that we have a closed selection and that `index` is valid
        if (!state().canEdit()) {
            throw new IllegalStateException("May not move point in state " + state());
        }
        if (index < 0 || index >= controlPoints.size()) {
            throw new IllegalArgumentException("Invalid point index " + index);
        }

        int indexBefore = (index == 0) ? controlPoints.size() - 1: index - 1;
        PolyLine beforeSegment = segments.get(indexBefore);
        PolyLine afterSegment = segments.get(index);

        controlPoints.set(index, new Point(newPos));
        segments.set(indexBefore, new PolyLine(beforeSegment.start(), controlPoints.get(index)));
        segments.set(index, new PolyLine(controlPoints.get(index), afterSegment.end()));

        propSupport.firePropertyChange("selection", null, selection());
    }

    /**
     * Close the current selection path by connecting the last segment to the starting point with a
     * straight line segment and transitioning to a "finished" state. If no segments have been added
     * yet, reset this selection instead. Transition state to selected, Throws an
     * `IllegalStateException` if the selection is already finished or cannot be finished.
     */
    public void finishSelection() {
        if (!state.canFinish()) {
            throw new IllegalStateException("Cannot finish a selection that is already finished");
        }
        if (segments.isEmpty()) {
            reset();
        } else {
            addPoint(controlPoints.getFirst());
            // Don't double-add the starting point
            controlPoints.removeLast();
            setState(PointToPointState.SELECTED);
        }
    }

    /**
     * Clear the current selection path and any starting point by calling super method.
     * Transition state to no selection.
     */
    @Override
    public void reset() {
        super.reset();
        setState(PointToPointState.NO_SELECTION);
    }

    /**
     * Adds first point to the selection by calling super method. Transition state to selecting.
     */
    @Override
    protected void startSelection(Point start) {
        assert start != null;

        super.startSelection(start);
        setState(PointToPointState.SELECTING);
    }

    /**
     * Remove the last control point from our selection. Notifies listeners that the "selection"
     * property has changed and transition state to selecting is selection was previously finished.
     */
    @Override
    protected void undoPoint() {
        if (segments.isEmpty()) {
            // Reset to remove the starting point
            reset();
        } else {
            segments.removeLast();
            if (state() == PointToPointState.SELECTED) {
                setState(PointToPointState.SELECTING);
            }
            else {
                controlPoints.removeLast();
            }
            propSupport.firePropertyChange("selection", null, selection());
        }
    }
}
