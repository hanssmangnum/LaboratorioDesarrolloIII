package yugioh.ui;

import yugioh.model.Card;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Componente que muestra una carta: imagen, nombre y ATK/DEF.
 * También puede mostrarse vacío o boca abajo, y marcarse como seleccionado o usado.
 */
public class CardPanel extends JPanel {

    public static final int IMAGE_WIDTH = 130;
    public static final int IMAGE_HEIGHT = 190;

    private static final Color BACKGROUND = new Color(250, 246, 235);
    private static final Color SELECTED = new Color(230, 160, 20);
    private static final Color NORMAL = new Color(150, 140, 120);
    private static final Border SELECTED_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SELECTED, 4), BorderFactory.createEmptyBorder(2, 2, 2, 2));
    private static final Border NORMAL_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(NORMAL, 1), BorderFactory.createEmptyBorder(5, 5, 5, 5));

    private final JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel nameLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel statsLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel footerLabel = new JLabel(" ", SwingConstants.CENTER);

    private Card card;
    private boolean selected;
    private boolean used;

    public CardPanel() {
        super(new BorderLayout(0, 4));
        setBackground(BACKGROUND);

        imageLabel.setPreferredSize(new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT));
        imageLabel.setForeground(Color.GRAY);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        statsLabel.setFont(statsLabel.getFont().deriveFont(12f));
        footerLabel.setFont(footerLabel.getFont().deriveFont(Font.ITALIC, 11f));
        footerLabel.setForeground(new Color(120, 60, 0));

        JPanel info = new JPanel(new GridLayout(0, 1));
        info.setOpaque(false);
        info.add(nameLabel);
        info.add(statsLabel);
        info.add(footerLabel);

        add(imageLabel, BorderLayout.CENTER);
        add(info, BorderLayout.SOUTH);
        clear(" ");
    }

    /** Muestra la carta. Si {@code image} es null se indica que la imagen no está disponible. */
    public void showCard(Card card, ImageIcon image) {
        this.card = card;
        this.used = false;
        this.selected = false;
        imageLabel.setIcon(image);
        imageLabel.setText(image == null ? "<html><center>Imagen no<br>disponible</center></html>" : "");
        nameLabel.setText(wrap(card.getName()));
        nameLabel.setToolTipText(card.getName() + " - " + card.getType());
        statsLabel.setText("ATK " + card.getAtk() + " / DEF " + card.getDef());
        footerLabel.setText(" ");
        refresh();
    }

    /** Muestra el reverso de una carta (cartas ocultas de la máquina). */
    public void showHidden() {
        this.card = null;
        imageLabel.setIcon(CARD_BACK);
        imageLabel.setText("");
        nameLabel.setText("???");
        nameLabel.setToolTipText(null);
        statsLabel.setText("ATK ? / DEF ?");
        footerLabel.setText(" ");
        refresh();
    }

    /** Deja el espacio vacío con un texto informativo. */
    public void clear(String message) {
        this.card = null;
        this.selected = false;
        this.used = false;
        imageLabel.setIcon(null);
        imageLabel.setText(message);
        nameLabel.setText(" ");
        nameLabel.setToolTipText(null);
        statsLabel.setText(" ");
        footerLabel.setText(" ");
        refresh();
    }

    public void setFooter(String text) {
        footerLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        refresh();
    }

    /** Marca la carta como ya jugada: se atenúa y deja de poder seleccionarse. */
    public void setUsed(boolean used) {
        this.used = used;
        this.selected = false;
        setFooter(used ? "Ya jugada" : null);
        refresh();
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean isUsed() {
        return used;
    }

    public Card getCard() {
        return card;
    }

    private void refresh() {
        setBorder(selected ? SELECTED_BORDER : NORMAL_BORDER);
        imageLabel.setEnabled(!used);
        nameLabel.setEnabled(!used);
        statsLabel.setEnabled(!used);
        repaint();
    }

    /** Escala una imagen descargada al tamaño de la carta (usar fuera del EDT). */
    public static ImageIcon scale(BufferedImage source) {
        BufferedImage scaled = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null);
        g.dispose();
        return new ImageIcon(scaled);
    }

    /** Nombre con salto de línea automático (los nombres de cartas pueden ser largos). */
    private static String wrap(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='text-align:center;width:" + (IMAGE_WIDTH - 30) + "px'>"
                + escaped + "</div></html>";
    }

    /** Reverso de carta dibujado por código, para no depender de imágenes externas. */
    private static final ImageIcon CARD_BACK = createCardBack(IMAGE_WIDTH, IMAGE_HEIGHT);

    public static ImageIcon createCardBack(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(120, 70, 30), width, height, new Color(60, 30, 10)));
        g.fillRoundRect(0, 0, width, height, 12, 12);
        g.setColor(new Color(230, 190, 90));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(4, 4, width - 8, height - 8, 10, 10);
        g.setColor(new Color(20, 10, 5));
        g.fillOval(width / 6, height / 4, width * 2 / 3, height / 2);
        g.setColor(new Color(230, 190, 90));
        g.drawOval(width / 6, height / 4, width * 2 / 3, height / 2);
        g.setFont(new Font(Font.SERIF, Font.BOLD, Math.max(12, width / 3)));
        String text = "?";
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (width - w) / 2, height / 2 + g.getFontMetrics().getAscent() / 3);
        g.dispose();
        return new ImageIcon(img);
    }
}
