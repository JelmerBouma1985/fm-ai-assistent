package com.github.fmaiassistent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.AWTError;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small native desktop window that provides startup feedback and owns the taskbar lifecycle.
 */
final class DesktopWindow implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DesktopWindow.class);

    private final JFrame frame;
    private final JLabel status;
    private final JProgressBar progress;
    private final JPanel actions;
    private final JButton openButton;
    private final JButton exitButton;
    private final AtomicReference<Runnable> openAction = new AtomicReference<>(() -> { });
    private final AtomicReference<Runnable> exitAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean exitRequested = new AtomicBoolean();

    private DesktopWindow(Components components, Runnable initialExitAction) {
        this.frame = components.frame();
        this.status = components.status();
        this.progress = components.progress();
        this.actions = components.actions();
        this.openButton = components.openButton();
        this.exitButton = components.exitButton();
        this.exitAction = new AtomicReference<>(initialExitAction);
    }

    static DesktopWindow show(Runnable initialExitAction) {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Desktop taskbar window is unavailable in a headless environment");
            return new DesktopWindow(Components.unavailable(), initialExitAction);
        }

        AtomicReference<Components> components = new AtomicReference<>();
        try {
            runOnEventThread(() -> {
                Components created = createComponents();
                created.frame().setVisible(true);
                components.set(created);
            });
            DesktopWindow window = new DesktopWindow(components.get(), initialExitAction);
            window.connectActions();
            return window;
        } catch (Exception | AWTError exception) {
            log.warn("Could not show the desktop taskbar window; startup will continue: {}",
                    message(exception));
            return new DesktopWindow(Components.unavailable(), initialExitAction);
        }
    }

    void applicationReady(Runnable openApplication, Runnable exitApplication) {
        openAction.set(openApplication);
        exitAction.set(exitApplication);
        if (frame == null || closed.get()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            if (closed.get()) {
                return;
            }
            status.setText("Application is running");
            progress.setVisible(false);
            actions.setVisible(true);
            openButton.setEnabled(true);
            exitButton.setEnabled(true);
            frame.pack();
            frame.setLocationRelativeTo(null);
            // Keep the application represented in the taskbar without leaving a control
            // window over the browser. Clicking its taskbar icon restores this window.
            frame.setState(Frame.ICONIFIED);
        });
    }

    @Override
    public void close() {
        if (frame == null || !closed.compareAndSet(false, true)) {
            return;
        }
        if (EventQueue.isDispatchThread()) {
            frame.dispose();
        } else {
            EventQueue.invokeLater(frame::dispose);
        }
    }

    private void connectActions() throws InvocationTargetException, InterruptedException {
        if (frame == null) {
            return;
        }
        runOnEventThread(() -> {
            openButton.addActionListener(ignored -> openAction.get().run());
            exitButton.addActionListener(ignored -> requestExit());
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    requestExit();
                }
            });
        });
    }

    private void requestExit() {
        if (!exitRequested.compareAndSet(false, true)) {
            return;
        }
        exitButton.setEnabled(false);
        status.setText("Shutting down…");
        Thread.ofPlatform()
                .name("desktop-shutdown")
                .start(exitAction.get());
    }

    private static Components createComponents() {
        JPanel content = new JPanel(new BorderLayout(16, 0));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(25, 91, 67), 2),
                BorderFactory.createEmptyBorder(20, 22, 20, 22)));

        JLabel icon = new JLabel(new javax.swing.ImageIcon(createIcon()));
        icon.setVerticalAlignment(SwingConstants.TOP);
        content.add(icon, BorderLayout.WEST);

        JPanel message = new JPanel();
        message.setLayout(new BoxLayout(message, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("FM AI Assistent");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JLabel status = new JLabel("Starting application…");
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(240, 8));

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        JButton openButton = new JButton("Open");
        JButton exitButton = new JButton("Exit");
        openButton.setEnabled(false);
        exitButton.setEnabled(false);
        actions.add(openButton);
        actions.add(exitButton);
        actions.setVisible(false);

        message.add(title);
        message.add(Box.createVerticalStrut(6));
        message.add(status);
        message.add(Box.createVerticalStrut(12));
        message.add(progress);
        message.add(Box.createVerticalStrut(12));
        message.add(actions);
        content.add(message, BorderLayout.CENTER);

        JFrame frame = new JFrame("FM AI Assistent");
        frame.setIconImage(createIcon());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(content);
        frame.setResizable(false);
        frame.pack();
        frame.setLocationRelativeTo(null);
        return new Components(frame, status, progress, actions, openButton, exitButton);
    }

    static BufferedImage createIcon() {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(25, 91, 67));
            graphics.fillRoundRect(1, 1, size - 2, size - 2, 8, 8);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics metrics = graphics.getFontMetrics();
            String label = "FM";
            int x = (size - metrics.stringWidth(label)) / 2;
            int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(label, x, y);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void runOnEventThread(Runnable operation)
            throws InvocationTargetException, InterruptedException {
        if (EventQueue.isDispatchThread()) {
            operation.run();
        } else {
            EventQueue.invokeAndWait(operation);
        }
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record Components(
            JFrame frame,
            JLabel status,
            JProgressBar progress,
            JPanel actions,
            JButton openButton,
            JButton exitButton) {
        private static Components unavailable() {
            return new Components(null, null, null, null, null, null);
        }
    }
}
