package scissors;

import graph.PathfindingSnapshot;
import graph.ShortestPaths;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.swing.SwingWorker;
import javax.swing.SwingWorker.StateValue;
import selector.PolyLine;
import selector.SelectionModel;

/**
 * Models a selection tool that connects each added point with a path determined by the "intelligent
 * scissors" algorithm using a configurable weight function.
 */
public class ScissorsSelectionModel extends SelectionModel {

    enum ScissorsState implements SelectionState {
        NO_SELECTION,
        SELECTING,
        SELECTED,
        ADDING,
        /**
         * The selection model is currently performing asynchronous processing in order to extend
         * the selection path or move one of its points.
         */
        MOVING;

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
            return this == ADDING || this == MOVING;
        }
    }

    private ScissorsState state;

    @Override
    public SelectionState state() {
        return state;
    }

    private void setState(ScissorsState newState) {
        ScissorsState oldState = state;
        state = newState;
        propSupport.firePropertyChange("state", oldState, newState);
    }

    /**
     * The graph representation of the current image being rendered and traced (null if inherited
     * `img` is null).
     */
    private ImageGraph graph;

    /**
     * Name of Weigher that will determine edge weights between neighboring pixels.  Must be
     * recognized by the `ScissorsWeights` factory.
     */
    private String weightName;

    /**
     * The shortest paths computed from the last committed point for the current image.  May be null
     * if we are not in the SELECTING state.
     */
    private PathfindingSnapshot paths;

    /**
     * The most recent intermediate paths from the current shortest paths solve.  Null if no ongoing
     * solve or if solve has not reported any progress yet.
     */
    private PathfindingSnapshot pendingPaths;

    /**
     * The SwingWorker currently being used to solve for shortest paths on a background thread. Note
     * that "progress" events from `worker` will be forwarded to our own property change listeners.
     * Null if state is not "processing".  Note: To support asynchronous cancellation, workers
     * should not make any changes to this model or forward any events if `worker` does not
     * currently point to them.
     */
    private ShortestPathsWorker worker;

    /**
     * Create a `ScissorsSelectionModel` in which the "intelligent scissors" algorithm will use the
     * weight function named `weightName` (as recognized by the `ScissorsWeights` factory). See
     * `SelectionModel` for interpretation of `notifyOnEdt`.
     */
    public ScissorsSelectionModel(String weightName, boolean notifyOnEdt) {
        super(notifyOnEdt);
        this.weightName = weightName;
        state = ScissorsState.NO_SELECTION;
    }

    /**
     * Create a `ScissorsSelectionModel` in which the "intelligent scissors" algorithm will use the
     * weight function named `weightName` (as recognized by the `ScissorsWeights` factory).
     * Initialize this model to represent the same selection of the same image as `copy` (but does
     * not copy any listeners from `copy`).  See `SelectionModel` for thread restrictions.
     */
    public ScissorsSelectionModel(String weightName, SelectionModel copy) {
        super(copy);
        this.weightName = weightName;
        if (image() != null) {
            graph = new ImageGraph(image());
        }
        if (copy instanceof ScissorsSelectionModel) {
            state = ((ScissorsSelectionModel) copy).state;
        } else {
            if (copy.state().isEmpty()) {
                assert segments.isEmpty() && controlPoints.isEmpty();
                state = ScissorsState.NO_SELECTION;
            } else if (!copy.state().isFinished() && controlPoints.size() == segments.size() + 1) {
                // Assumes segments start and end at control points
                state = ScissorsState.SELECTING;
                findPaths(graph.idAt(controlPoints.getLast()));
            } else if (copy.state().isFinished() && controlPoints.size() == segments.size()) {
                // Assumes segments start and end at control points
                state = ScissorsState.SELECTED;
            } else {
                reset();
            }
        }
    }

    @Override
    public void reset() {
        // Overridden due to the need to cancel background processing.

        // Cancel any background tasks
        if (state().isProcessing()) {
            cancelProcessing();
        }

        // Since the inherited behavior will immediately transition to the NO_SELECTION state,
        // we need to maintain our invariant regarding `worker` by setting to null.  Any running
        // worker will notice this and refrain from changing us.
        worker = null;

        super.reset();
        setState(ScissorsState.NO_SELECTION);
    }

    @Override
    public void setImage(BufferedImage img) {
        // Overridden due to the need to update our graph

        super.setImage(img);

        // In addition to whatever our superclass does to set the image, we also need to replace our
        //  graph with one that represents the new image.
        if (img != null) {
            graph = new ImageGraph(img);
        } else {
            graph = null;
        }
    }

    @Override
    protected void startSelection(Point start) {
        super.startSelection(start);

        // Find shortest paths from our start node so we will know what segment to connect to the
        //  next added (or live wire) point.
        int committedId = graph.idAt(start);
        findPaths(committedId);
    }

    @Override
    public void finishSelection() {
        // Overridden to skip the processing that otherwise runs when a point is added

        if (!state().canFinish()) {
            throw new IllegalStateException("Cannot finish a selection that is already finished");
        }
        if (segments.isEmpty()) {
            reset();
        } else {
            int committedId = graph.idAt(controlPoints.getFirst());
            PolyLine newSegment = graph.pathToPolyLine(paths.pathTo(committedId));
            segments.addLast(newSegment);
            setState(ScissorsState.SELECTED);
        }
    }

    @Override
    protected void undoPoint() {
        if (segments.isEmpty()) {
            // Reset to remove the starting point
            reset();
        } else {
            segments.removeLast();
            if (state == ScissorsState.SELECTED) {
                setState(ScissorsState.SELECTING);
            } else {
                controlPoints.removeLast();
            }
            propSupport.firePropertyChange("selection", null, selection());
            findPaths(graph.idAt(controlPoints.getLast()));
        }
    }

    /**
     * Append to the current selection the "intelligent scissors" shortest path segment connecting
     * our current selection's endpoint to `p`.
     */
    @Override
    protected void appendToSelection(Point p) {
        assert state().canAddPoint();
        assert p != null;

        segments.add(liveWire(p));
        controlPoints.add(new Point(p));
        findPaths(graph.idAt(p));
    }

    /**
     * Transition to the ADDING or MOVING state as appropriate and start solving for shortest paths
     * from the vertex with ID `startId`.  Preserves invariants associated with `worker`,
     * `pendingPaths`, and `previousState`.
     */
    private void findPaths(int startId) {
        if (state().canAddPoint()) {
            setState(ScissorsState.ADDING);
        } else {
            setState(ScissorsState.MOVING);
        }

        pendingPaths = null;
        worker = new ShortestPathsWorker(startId);
        worker.execute();
    }

    @Override
    public void movePoint(int index, Point newPos) {
        assert state().canEdit();
        assert segments.size() >= 2;

        // Start solving for shortest paths from the moved point, which will give us the segments to
        //  both the preceding and succeeding points.  This will transition us to the MOVING
        //  state.
        findPaths(graph.idAt(newPos));

        // Our worker's `done()` method is sufficient for adding points, but we need to do more
        //  in order to move one.  This is one way to tack additional work onto a task (it will run
        //  on the EDT).
        worker.addPropertyChangeListener(e -> {
            ShortestPathsWorker src = (ShortestPathsWorker) e.getSource();
            if ("state".equals(e.getPropertyName()) && e.getNewValue() == StateValue.DONE) {
                // If the solve wasn't cancelled, use its results to compute the replacement
                //  segments.
                if (src.state() == Future.State.SUCCESS) {
                    ListIterator<PolyLine> it = segments.listIterator(index + 1);
                    PolyLine oldAfter = it.previous();
                    var oaEnd = graph.vertexAt(oldAfter.end());
                    // New segment is path from moved point to successor point
                    it.set(graph.pathToPolyLine(paths.pathTo(oaEnd.id())));

                    if (!it.hasPrevious()) {
                        it = segments.listIterator(segments.size());
                        controlPoints.set(0, new Point(newPos));
                    }
                    PolyLine oldBefore = it.previous();
                    var obStart = graph.vertexAt(oldBefore.start());
                    // New segment is the reverse of the path from the moved point to its
                    //  predecessor point.
                    it.set(graph.pathToPolyLine(paths.pathTo(obStart.id()).reversed()));

                    controlPoints.set(index, new Point(newPos));

                    propSupport.firePropertyChange("selection", null, selection());
                }
            }
        });
    }

    /**
     * Returns the "intelligent scissors" shortest path segment connecting our current selection's
     * endpoint to `p`.
     */
    @Override
    public PolyLine liveWire(Point p) {
        assert p!= null;

        int vertexId = graph.idAt(p);
        return graph.pathToPolyLine(paths.pathTo(vertexId));
    }

    /**
     * Return the most recent pathfinding snapshot produced by our "intelligent scissors"
     * processing, which attempts to find good paths from the end of our selection to all other
     * pixels in the image.  Returns null if our processing has not yet produced any results.
     */
    @Override
    public ImagePathsSnapshot getProcessingProgress() {
        assert state().isProcessing();
        return (pendingPaths != null) ? new ImagePathsSnapshot(graph, pendingPaths) : null;
    }

    @Override
    public void cancelProcessing() {
        assert state().isProcessing();

        if (worker != null) {
            worker.cancel(false);
        }
    }


    /**
     * SwingWorker for computing shortest paths and periodically updating progress.  This is an
     * inner class, meaning it has access to all fields of its containing TraceComponent. It
     * publishes preliminary pathfinding results (containing settled and frontier nodes) and returns
     * the final best paths (or null if cancelled).
     */
    private class ShortestPathsWorker
            extends SwingWorker<PathfindingSnapshot, PathfindingSnapshot> {

        /**
         * The shortest-paths solver this worker will use.  After construction, must only be
         * accessed from the background thread.
         */
        private final ShortestPaths<ImageVertex, ImageEdge> pathfinder;

        /**
         * Construct a worker that, when executed, will find the shortest paths from the vertex with
         * ID `startId` to every pixel in our outer model's `image`.  "progress" events will be
         * forwarded to our outer model's listeners.  This must be called from the EDT.
         */
        public ShortestPathsWorker(int startId) {
            pathfinder = new ShortestPaths<>(
                    graph, ScissorsWeights.makeWeigher(weightName, graph));
            pathfinder.setStart(startId);

            // Forward progress property changes to outer model's listeners (as long as we are
            // still the active solver).
            addPropertyChangeListener((PropertyChangeEvent evt) -> {
                if ("progress".equals(evt.getPropertyName()) && worker == this) {
                    propSupport.firePropertyChange(evt);
                }
            });
        }

        /**
         * Solve for shortest paths and return the results.  Periodically publish progress
         * percentage and preliminary shortest paths.  It is assumed that this is generally not
         * called from the EDT.  Returns null if cancelled.
         */
        @Override
        public PathfindingSnapshot doInBackground() {
            // This is executed on a separate thread; do not access outer model's fields!

            int pathsSettled = 0;
            int batchSize = 5000;
            PathfindingSnapshot pfs = null;
            while (!pathfinder.allPathsFound()) {
                pfs = pathfinder.extendSearch(batchSize);
                pathsSettled += batchSize;
                int percentage = pathsSettled * 100 / graph.vertexCount();
                if (percentage > 100) {percentage = 100;}
                setProgress(percentage);
                publish(pfs);
                if (isCancelled()) {
                    return null;
                }
            }
            return pfs;
        }

        /**
         * Save the most recent preliminary paths published by the worker's background task to our
         * outer Model's `pendingPaths` and notify listeners that the "pending-paths" property has
         * changed.  This is executed on the EDT.
         */
        @Override
        public void process(List<PathfindingSnapshot> publishedPaths) {
            // If our outer component has changed its image and state since we started, do nothing.
            if (worker != this) {
                return;
            }
            pendingPaths = publishedPaths.getLast();
            propSupport.firePropertyChange("pending-paths", null, pendingPaths);
        }

        /**
         * This is executed on the EDT.  If we are still the active worker, update model's paths on
         * success, or undo any added segment if cancelled (notifying observers).  Transition model
         * to next state as appropriate.
         */
        @Override
        public void done() {
            if (worker != this) {
                return;
            }
            try {
                paths = get();
                if (state == ScissorsState.ADDING) {
                    setState(ScissorsState.SELECTING);
                }
                else if (state == ScissorsState.MOVING) {
                    setState(ScissorsState.SELECTED);
                }
            }
            catch (CancellationException e) {
                if (state == ScissorsState.ADDING) {
                    undoPoint();
                    setState(segments.isEmpty() ? ScissorsState.NO_SELECTION : ScissorsState.SELECTING);
                    propSupport.firePropertyChange("selection", null, selection());
                } else if (state == ScissorsState.MOVING) {
                    setState(ScissorsState.SELECTED);
                }
            }
            catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
            catch (InterruptedException e) {

            }
            finally {
                worker = null;
            }
        } // done
    } // class ShortestPathWorker
}
