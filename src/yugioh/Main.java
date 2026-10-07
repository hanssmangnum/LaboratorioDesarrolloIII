package yugioh;

import yugioh.ui.DuelFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;


public class Main {

    public static void main(String[] args) {
        // Toda la interfaz se crea en el hilo de eventos de Swing (EDT)
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Si falla se usa el look and feel por defecto
            }
            new DuelFrame().setVisible(true);
        });
    }
}
