package selector;

import java.awt.Point;
import java.util.List;
import selector.PointToPointSelectionModel.PointToPointState;

public class CircleSelectionModel extends SelectionModel {
    enum CircleState implements SelectionState {
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
    private CircleState state;

    /**
     * Create a model instance with no selection and no image.  If `notifyOnEdt` is true, property
     * change listeners will be notified on Swing's Event Dispatch thread, regardless of which
     * thread the event was fired from.
     */
    public CircleSelectionModel(boolean notifyOnEdt) {
        super(notifyOnEdt);
        state = CircleState.NO_SELECTION;
    }

    @Override
    public SelectionState state() {
        return state;
    }

    /**
     * Change our selection state to `newState` (internal operation).  This should only be used to
     * perform valid state transitions.  Notifies listeners that the "state" property has changed.
     */
    private void setState(CircleState newState) {
        CircleState oldState = state;
        state = newState;
        propSupport.firePropertyChange("state", oldState, newState);
    }

    /**
     * Return a circle of radius 'p'.
     */
    @Override
    public PolyLine liveWire(Point p) {
        assert p != null;

        return generateCircleSegment(p);
    }
    /**
     * Add `p` as the next control point of our selection, transitioning state to selecting if
     * first point or finishing selection if added second point.
     */
    @Override
    protected void appendToSelection(Point p) {
        assert p != null;

        if (segments.isEmpty()) {
            setState(CircleState.SELECTING);
        }
        controlPoints.add(new Point(p));
        if (controlPoints.size() == 2) {
            segments.add(generateCircleSegment(p));
            finishSelection();
        }
    }

    /**
     * Move the control point with index `index` to `newPos`. If the center point is moved, move
     * the whole selection, otherwise change the radius of the circle. Notify listeners that the
     * "selection" property has changed.
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

        controlPoints.set(index, new Point(newPos));
        segments.set(0, generateCircleSegment(controlPoints.get(1)));
        propSupport.firePropertyChange("selection", null, selection());
    }

    /**
     * If no segments have been added yet, reset this selection instead. Transition state to
     * selected, Throws a `IllegalStateException` if the selection is already finished or cannot be
     * finished.
     */
    public void finishSelection() {
        if (!state.canFinish()) {
            throw new IllegalStateException("Cannot finish a selection that is already finished");
        }
        if (segments.isEmpty()) {
            reset();
        } else {
            setState(CircleState.SELECTED);
        }
    }

    /**
     * Clear the current selection path and any starting point by calling super method. Transition
     * state to no selection.
     */
    @Override
    public void reset() {
        super.reset();
        setState(CircleState.NO_SELECTION);
    }

    /**
     * Adds first point to the selection by calling super method. Transition state to selecting.
     */
    @Override
    protected void startSelection(Point start) {
        assert start != null;

        super.startSelection(start);
        setState(CircleState.SELECTING);
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
            if (state() == CircleState.SELECTED) {
                setState(CircleState.SELECTING);
            }
            controlPoints.removeLast();
            propSupport.firePropertyChange("selection", null, selection());
        }
    }

    /**
     * Return a PolyLine where all the x and y values are points on the circle.
     */
    protected PolyLine generateCircleSegment(Point p) {
        assert p != null;

        Point center = controlPoints.getFirst();
        double radius = Math.sqrt(Math.pow(p.x - center.x, 2) + Math.pow(p.y - center.y, 2));

        // calculate angle increment based on length of radius
        double numSegments = radius * 1000;
        double angleIncrement = 2 * Math.PI / numSegments;

        int[] xPoints = new int[(int) numSegments + 1];
        int[] yPoints = new int[(int) numSegments + 1];

        for (int i = 0; i <= numSegments; i++) {
            double angle = angleIncrement * i;
            xPoints[i] = (int) (center.x + radius * Math.cos(angle));
            yPoints[i] = (int) (center.y + radius * Math.sin(angle));
        }

        return new PolyLine(xPoints, yPoints);
    }
}
