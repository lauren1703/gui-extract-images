package selector;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import selector.SelectionModel.SelectionState;
import scissors.ScissorsSelectionModel;

/**
 * A graphical application for selecting and extracting regions of images.
 */
public class SelectorApp implements PropertyChangeListener {

    /**
     * Our application window.  Disposed when application exits.
     */
    private final JFrame frame;

    /**
     * Component for displaying the current image and selection tool.
     */
    private final ImagePanel imgPanel;

    /**
     * The current state of the selection tool.  Must always match the model used by `imgPanel`.
     */
    private SelectionModel model;

    /**
     * Progress bar to indicate the progress of a model that needs to do long calculations in a
     * "processing" state.
     */
    private JProgressBar processingProgress;

    /* Components whose state must be changed during the selection process. */
    private JMenuItem saveItem;
    private JMenuItem undoItem;
    private JButton cancelButton;
    private JButton undoButton;
    private JButton resetButton;
    private JButton finishButton;
    private final JLabel statusLabel;


    /**
     * Construct a new application instance.  Initializes GUI components, so must be invoked on the
     * Swing Event Dispatch Thread.  Does not show the application window (call `start()` to do
     * that).
     */
    public SelectorApp() {
        // Initialize application window
        frame = new JFrame("Selector");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // Add progress bar
        processingProgress = new JProgressBar();
        frame.add(processingProgress, BorderLayout.PAGE_START);

        // Add status bar
        statusLabel = new JLabel();
        frame.add(statusLabel, BorderLayout.PAGE_END);

        // Add image component with scrollbars
        imgPanel = new ImagePanel();
        JScrollPane scrollPane = new JScrollPane(imgPanel);
        scrollPane.setPreferredSize(new Dimension(500, 500));
        frame.add(scrollPane);


        // Add menu bar
        frame.setJMenuBar(makeMenuBar());

        // Add control buttons
        frame.add(makeControlPanel(), BorderLayout.EAST);

        // Controller: Set initial selection tool and update components to reflect its state
        setSelectionModel(new PointToPointSelectionModel(true));
    }

    /**
     * Create and populate a menu bar with our application's menus and items and attach listeners.
     * Should only be called from constructor, as it initializes menu item fields.
     */
    private JMenuBar makeMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Create and populate File menu
        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);
        JMenuItem openItem = new JMenuItem("Open...");
        fileMenu.add(openItem);
        saveItem = new JMenuItem("Save...");
        fileMenu.add(saveItem);
        JMenuItem closeItem = new JMenuItem("Close");
        fileMenu.add(closeItem);
        JMenuItem exitItem = new JMenuItem("Exit");
        fileMenu.add(exitItem);

        // Create and populate Edit menu
        JMenu editMenu = new JMenu("Edit");
        menuBar.add(editMenu);
        undoItem = new JMenuItem("Undo");
        editMenu.add(undoItem);

        // Add keyboard shortcuts
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.META_DOWN_MASK));
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK));
        closeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK));
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.META_DOWN_MASK));
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK));

        // Controller: Attach menu item listeners
        openItem.addActionListener(e -> openImage());
        closeItem.addActionListener(e -> imgPanel.setImage(null));
        saveItem.addActionListener(e -> saveSelection());
        exitItem.addActionListener(e -> frame.dispose());
        undoItem.addActionListener(e -> model.undo());
        frame.revalidate();
        frame.repaint();
        return menuBar;
    }

    /**
     * Return a panel containing buttons for controlling image selection.  Should only be called
     * from constructor, as it initializes button fields.
     */
    private JPanel makeControlPanel() {
        JPanel controlPanel = new JPanel();
        GridLayout grid = new GridLayout(5, 1, 0, 10);
        controlPanel.setLayout(grid);
        cancelButton = new JButton("Cancel");
        undoButton = new JButton("Undo");
        resetButton = new JButton("Reset");
        finishButton = new JButton("Finish");

        cancelButton.addActionListener(e -> model.cancelProcessing());
        undoButton.addActionListener(e -> model.undo());
        resetButton.addActionListener(e -> model.reset());
        finishButton.addActionListener(e -> model.finishSelection());

        String[] models = {"Point-to-point", "Spline", "Circle",
                "Intelligent scissors - Greyscale", "Intelligent scissors - Color"};
        JComboBox<String> comboBox = new JComboBox<>(models);

        comboBox.addActionListener(e -> {
            String selected = (String) comboBox.getSelectedItem();
            if (selected.equals("Point-to-point")) {
                setSelectionModel(new PointToPointSelectionModel(model));
            }
            else if (selected.equals("Spline")) {
                setSelectionModel(new SplineSelectionModel(model));
            }
            else if (selected.equals("Intelligent scissors - Greyscale")) {
                setSelectionModel(new ScissorsSelectionModel("CrossGradMono", true));
            }
            else if (selected.equals("Intelligent scissors - Color")) {
                setSelectionModel(new ScissorsSelectionModel("CrossGradColor", true));
            }
            else {
                setSelectionModel(new CircleSelectionModel(true));
            }
        } );

        controlPanel.add(comboBox);
        controlPanel.add(cancelButton);
        controlPanel.add(undoButton);
        controlPanel.add(resetButton);
        controlPanel.add(finishButton);
        return controlPanel;
    }

    /**
     * Start the application by showing its window.
     */
    public void start() {
        // Compute ideal window size
        frame.pack();

        frame.setVisible(true);
    }

    /**
     * React to property changes in an observed model.  Supported properties include:
     * * "state": Update components to reflect the new selection state.
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if ("state".equals(evt.getPropertyName())) {
                reflectSelectionState(model.state());
                if ("processing".equals(evt.getNewValue())) {
                    processingProgress.setIndeterminate(true);
                }
                else {
                    processingProgress.setValue(0);
                    processingProgress.setIndeterminate(false);
                }
        }
        if ("progress".equals(evt.getPropertyName())) {
            processingProgress.setValue((int) evt.getNewValue());
            processingProgress.setIndeterminate(false);
        }
    }

    /**
     * Update components to reflect a selection state of `state`.  Disable buttons and menu items
     * whose actions are invalid in that state, and update the status bar.
     */
    private void reflectSelectionState(SelectionState state) {
        // Update status bar to show current state
        statusLabel.setText(state.toString());

        cancelButton.setEnabled(state.isProcessing());
        resetButton.setEnabled(!state.isEmpty());
        undoItem.setEnabled(state.canUndo());
        undoButton.setEnabled(state.canUndo());
        finishButton.setEnabled(state.canFinish());
        saveItem.setEnabled(state.isFinished());
    }

    /**
     * Return the model of the selection tool currently in use.
     */
    public SelectionModel getSelectionModel() {
        return model;
    }

    /**
     * Use `newModel` as the selection tool and update our view to reflect its state.  This
     * application will no longer respond to changes made to its previous selection model and will
     * instead respond to property changes from `newModel`.
     */
    public void setSelectionModel(SelectionModel newModel) {
        // Stop listening to old model
        if (model != null) {
            model.removePropertyChangeListener(this);
        }

        imgPanel.setSelectionModel(newModel);
        model = imgPanel.selection();
        model.addPropertyChangeListener("state", this);

        // Since the new model's initial state may be different from the old model's state, manually
        //  trigger an update to our state-dependent view.
        reflectSelectionState(model.state());

        // New in A6: Listen for "progress" events
        model.addPropertyChangeListener("progress", this);
    }

    /**
     * Start displaying and selecting from `img` instead of any previous image.  Argument may be
     * null, in which case no image is displayed and the current selection is reset.
     */
    public void setImage(BufferedImage img) {
        imgPanel.setImage(img);
    }

    /**
     * Allow the user to choose a new image from an "open" dialog.  If they do, start displaying and
     * selecting from that image.  Show an error message dialog (and retain any previous image) if
     * the chosen image could not be opened.
     */
    private void openImage() {
        JFileChooser chooser = new JFileChooser();
        // Start browsing in current directory
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        // Filter for file extensions supported by Java's ImageIO readers
        chooser.setFileFilter(new FileNameExtensionFilter("Image files",
                ImageIO.getReaderFileSuffixes()));

        int returnVal = chooser.showDialog(null, "Open file");

        while (true) {
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                try {
                    BufferedImage bufferedImg = ImageIO.read(file);
                    if (bufferedImg == null) {
                        JOptionPane.showMessageDialog(new JFrame(),
                                "Unsupported image format at " + file.getPath(),
                                "Unsupported Image Format",
                                JOptionPane.ERROR_MESSAGE);
                    } else {
                        setImage(bufferedImg);
                        return;
                    }
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(new JFrame(),
                            "Could not read the image at " + file.getPath(),
                            "Unsupported Image Format",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
            else if (returnVal == JFileChooser.ERROR_OPTION) {
                JOptionPane.showMessageDialog(new JFrame(),
                        "Error occurred opening image",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
            else {
                return;
            }
            returnVal = chooser.showDialog(null, "Open file");
        }
    }

    /**
     * Save the selected region of the current image to a file selected from a "save" dialog.
     * Show an error message dialog if the image could not be saved.
     */
    private void saveSelection() {
        JFileChooser chooser = new JFileChooser();
        // Start browsing in current directory
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        // We always save in PNG format, so only show existing PNG files
        chooser.setFileFilter(new FileNameExtensionFilter("PNG images", "png"));

        int returnVal = chooser.showDialog(null, "Save file");
        while(true) {
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File selectedFile = chooser.getSelectedFile();
                String filename = selectedFile.getName();

                // Check if filename ends with ".png"; if not, append ".png"
                if (!filename.toLowerCase().endsWith(".png")) {
                    selectedFile = new File(selectedFile.getAbsolutePath() + ".png");
                }
                try {
                    OutputStream outputFile = new FileOutputStream(selectedFile);
                    Object[] options = {"Yes", "No", "Cancel"};
                    int n = JOptionPane.showOptionDialog(frame,
                            "Are you sure you want to overwrite the file selected?",
                            "Confirm",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            options,
                            options[2]);
                    if (n == 0) {
                        model.saveSelection(outputFile);
                        return;
                    }
                    else if (n == 2) {
                        return;
                    }
                }
                catch (FileNotFoundException e) {
                    JOptionPane.showMessageDialog(null,
                            e.getMessage(),
                            "File Not Found Exception", JOptionPane.ERROR_MESSAGE);
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(null,
                            e.getMessage(),
                            "IOException", JOptionPane.ERROR_MESSAGE);
                }
            }
            else if (returnVal == JFileChooser.ERROR_OPTION) {
                JOptionPane.showMessageDialog(new JFrame(),
                        "Error occurred opening image",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
            else {
                return;
            }
            returnVal = chooser.showDialog(null, "Save file");
        }
    }

    /**
     * Run an instance of SelectorApp.  No program arguments are expected.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Set Swing theme to look the same (and less old) on all operating systems.
            try {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            } catch (Exception ignored) {
                /* If the Nimbus theme isn't available, just use the platform default. */
            }

            // Create and start the app
            SelectorApp app = new SelectorApp();
            app.start();
        });
    }
}
