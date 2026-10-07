package yugioh.ui;

import yugioh.api.ApiException;
import yugioh.api.YgoApiClient;
import yugioh.logic.BattleListener;
import yugioh.logic.Duel;
import yugioh.model.Card;
import yugioh.model.Position;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Ventana principal del duelo.
 * <p>
 * La carga de cartas e imágenes se hace en un {@link SwingWorker} para no bloquear
 * el hilo de la interfaz (EDT). La lógica del duelo vive en {@link Duel}; esta clase
 * solo la escucha a través de {@link BattleListener} y actualiza la pantalla.
 */
public class DuelFrame extends JFrame implements BattleListener {

    /** Estados de la ventana; determinan qué botones están habilitados. */
    private enum State { LOADING, LOAD_FAILED, READY, PLAYING, FINISHED }

    private static final int TOTAL_CARDS = Duel.CARDS_PER_PLAYER * 2;
    /** Límite de peticiones al buscar 6 cartas distintas (evita bucles infinitos). */
    private static final int MAX_FETCHES = 30;
    private static final ImageIcon SMALL_BACK = CardPanel.createCardBack(48, 70);

    private final YgoApiClient api = new YgoApiClient();
    private final Random random = new Random();

    private final JLabel scoreLabel = new JLabel("Jugador 0 - 0 Máquina", SwingConstants.CENTER);
    private final JLabel turnLabel = new JLabel(" ", SwingConstants.CENTER);
    private final List<CardPanel> playerCardPanels = new ArrayList<>();
    private final List<JLabel> aiBackLabels = new ArrayList<>();
    private final CardPanel fieldPlayer = new CardPanel();
    private final CardPanel fieldAi = new CardPanel();
    private final JTextArea logArea = new JTextArea();
    private final JButton startButton = new JButton("Iniciar duelo");
    private final JButton chooseButton = new JButton("Elegir carta");
    private final JComboBox<Position> positionCombo = new JComboBox<>(Position.values());

    private List<Card> playerCards = new ArrayList<>();
    private List<Card> aiCards = new ArrayList<>();
    private Map<Integer, ImageIcon> images = new HashMap<>();
    private Duel duel;
    private State state;

    public DuelFrame() {
        super("Yu-Gi-Oh! Duel Lite");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        buildLayout();
        registerListeners();
        setMinimumSize(new Dimension(1150, 700));
        setLocationRelativeTo(null);
        loadCards();
    }

    // ------------------------------------------------------------------ construcción de la UI

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.setBackground(new Color(40, 44, 52));
        setContentPane(root);

        // Encabezado: título, marcador y turno
        JLabel title = new JLabel("Yu-Gi-Oh! Duel Lite", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(new Color(240, 200, 90));
        scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 18f));
        scoreLabel.setForeground(Color.WHITE);
        turnLabel.setFont(turnLabel.getFont().deriveFont(14f));
        turnLabel.setForeground(new Color(200, 210, 230));
        JPanel header = new JPanel(new GridLayout(0, 1));
        header.setOpaque(false);
        header.add(title);
        header.add(scoreLabel);
        header.add(turnLabel);
        root.add(header, BorderLayout.NORTH);

        // Mano de la máquina (oculta)
        JPanel aiHand = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        aiHand.setOpaque(false);
        aiHand.setBorder(titled("Mano de la máquina"));
        for (int i = 0; i < Duel.CARDS_PER_PLAYER; i++) {
            JLabel back = new JLabel();
            back.setPreferredSize(new Dimension(48, 70));
            aiBackLabels.add(back);
            aiHand.add(back);
        }

        // Mano del jugador (seleccionable)
        JPanel playerHand = new JPanel(new GridLayout(1, Duel.CARDS_PER_PLAYER, 10, 0));
        playerHand.setOpaque(false);
        playerHand.setBorder(titled("Tu mano (haz clic en una carta)"));
        for (int i = 0; i < Duel.CARDS_PER_PLAYER; i++) {
            CardPanel panel = new CardPanel();
            playerCardPanels.add(panel);
            playerHand.add(panel);
        }

        // Campo de batalla: última carta jugada por cada lado
        JLabel vs = new JLabel("VS", SwingConstants.CENTER);
        vs.setFont(vs.getFont().deriveFont(Font.BOLD, 26f));
        vs.setForeground(new Color(240, 200, 90));
        JPanel field = new JPanel(new BorderLayout(10, 0));
        field.setOpaque(false);
        field.setBorder(titled("Campo de batalla"));
        field.add(labeled("Tú", fieldPlayer), BorderLayout.WEST);
        field.add(vs, BorderLayout.CENTER);
        field.add(labeled("Máquina", fieldAi), BorderLayout.EAST);

        JPanel board = new JPanel(new BorderLayout(10, 10));
        board.setOpaque(false);
        board.add(aiHand, BorderLayout.NORTH);
        board.add(playerHand, BorderLayout.CENTER);
        board.add(field, BorderLayout.EAST);
        root.add(board, BorderLayout.CENTER);

        // Log de batalla desplazable
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(330, 0));
        logScroll.setBorder(titled("Log de batalla"));
        logScroll.setOpaque(false);
        logScroll.getViewport().setOpaque(true);
        root.add(logScroll, BorderLayout.EAST);

        // Controles
        JLabel positionLabel = new JLabel("Posición:");
        positionLabel.setForeground(Color.WHITE);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        controls.setOpaque(false);
        controls.add(positionLabel);
        controls.add(positionCombo);
        controls.add(chooseButton);
        controls.add(Box.createHorizontalStrut(30));
        controls.add(startButton);
        root.add(controls, BorderLayout.SOUTH);
    }

    private void registerListeners() {
        // ActionListener del botón principal: iniciar duelo, reintentar carga o nuevo duelo
        startButton.addActionListener(e -> {
            if (state == State.READY) {
                startDuel();
            } else if (state == State.LOAD_FAILED || state == State.FINISHED) {
                loadCards();
            }
        });

        // ActionListener de "Elegir carta": juega el turno con la carta seleccionada
        chooseButton.addActionListener(e -> playSelectedCard());

        // Clic sobre una carta de la mano para seleccionarla
        for (CardPanel panel : playerCardPanels) {
            panel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectCard(panel);
                }
            });
        }
    }

    // ------------------------------------------------------------------ carga de cartas (API)

    /** Pide 6 monstruos distintos a la API y sus imágenes en segundo plano. */
    private void loadCards() {
        setState(State.LOADING);
        duel = null;
        playerCards = new ArrayList<>();
        aiCards = new ArrayList<>();
        images = new HashMap<>();
        scoreLabel.setText("Jugador 0 - 0 Máquina");
        turnLabel.setText("Cargando cartas desde YGOProDeck...");
        for (CardPanel panel : playerCardPanels) {
            panel.clear("Cargando...");
        }
        for (JLabel back : aiBackLabels) {
            back.setIcon(null);
        }
        fieldPlayer.clear(" ");
        fieldAi.clear(" ");
        log("=== Cargando cartas desde la API ===");

        new SwingWorker<List<Card>, String>() {
            private final Map<Integer, ImageIcon> loadedImages = new HashMap<>();

            @Override
            protected List<Card> doInBackground() throws ApiException {
                List<Card> cards = new ArrayList<>();
                Set<Integer> ids = new HashSet<>();
                int fetches = 0;
                while (cards.size() < TOTAL_CARDS) {
                    if (++fetches > MAX_FETCHES) {
                        throw new ApiException("No se pudo cargar la carta: la API devolvió demasiadas repetidas.");
                    }
                    Card card = api.fetchRandomMonster();
                    if (!ids.add(card.getId())) {
                        continue; // carta repetida: pedir otra
                    }
                    cards.add(card);
                    String owner = cards.size() <= Duel.CARDS_PER_PLAYER ? "Jugador" : "Máquina";
                    publish("Carta " + cards.size() + "/" + TOTAL_CARDS + " (" + owner + ") cargada"
                            + (owner.equals("Jugador") ? ": " + card : ""));
                    try {
                        loadedImages.put(card.getId(), CardPanel.scale(api.downloadImage(card.getImageUrl())));
                    } catch (ApiException e) {
                        // La imagen no es indispensable para jugar: se avisa y se continúa
                        publish("Aviso: no se pudo cargar la imagen de " + card.getName() + ". " + e.getMessage());
                    }
                }
                return cards;
            }

            @Override
            protected void process(List<String> messages) {
                messages.forEach(DuelFrame.this::log);
            }

            @Override
            protected void done() {
                try {
                    List<Card> cards = get();
                    images = loadedImages;
                    onCardsLoaded(cards);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause instanceof ApiException
                            ? cause.getMessage()
                            : "No se pudo cargar la carta: " + cause;
                    onLoadFailed(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    onLoadFailed("La carga fue interrumpida.");
                }
            }
        }.execute();
    }

    private void onCardsLoaded(List<Card> cards) {
        playerCards = new ArrayList<>(cards.subList(0, Duel.CARDS_PER_PLAYER));
        aiCards = new ArrayList<>(cards.subList(Duel.CARDS_PER_PLAYER, TOTAL_CARDS));
        for (int i = 0; i < playerCardPanels.size(); i++) {
            Card card = playerCards.get(i);
            playerCardPanels.get(i).showCard(card, images.get(card.getId()));
        }
        for (JLabel back : aiBackLabels) {
            back.setIcon(SMALL_BACK);
        }
        log("Cartas listas. Pulsa \"Iniciar duelo\".");
        turnLabel.setText("Cartas listas. Pulsa \"Iniciar duelo\" para comenzar.");
        setState(State.READY);
    }

    private void onLoadFailed(String message) {
        log("ERROR: " + message);
        turnLabel.setText("No se pudieron cargar las cartas.");
        for (CardPanel panel : playerCardPanels) {
            panel.clear("Sin carta");
        }
        setState(State.LOAD_FAILED);
        JOptionPane.showMessageDialog(this, message, "Error al cargar cartas", JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ acciones del jugador

    private void startDuel() {
        // Validación: no se inicia sin las 3 cartas de cada jugador
        if (playerCards.size() != Duel.CARDS_PER_PLAYER || aiCards.size() != Duel.CARDS_PER_PLAYER) {
            JOptionPane.showMessageDialog(this, "Ambos jugadores deben tener sus 3 cartas cargadas.",
                    "No se puede iniciar", JOptionPane.WARNING_MESSAGE);
            return;
        }
        duel = new Duel(playerCards, aiCards, random);
        duel.addBattleListener(this);
        setState(State.PLAYING);
        log("");
        log("=== ¡Comienza el duelo! Primero en ganar " + Duel.ROUNDS_TO_WIN + " rondas ===");
        duel.start();
    }

    private void selectCard(CardPanel panel) {
        if (state != State.PLAYING || panel.isUsed() || panel.getCard() == null) {
            return;
        }
        for (CardPanel p : playerCardPanels) {
            p.setSelected(p == panel);
        }
        chooseButton.setEnabled(true);
    }

    private void playSelectedCard() {
        CardPanel selected = null;
        for (CardPanel p : playerCardPanels) {
            if (p.isSelected()) {
                selected = p;
            }
        }
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Primero selecciona una de tus cartas.",
                    "Elegir carta", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        selected.setUsed(true);
        chooseButton.setEnabled(false);
        duel.playTurn(selected.getCard(), (Position) positionCombo.getSelectedItem());
    }

    // ------------------------------------------------------------------ BattleListener
    // Duel se ejecuta en el EDT (lo invocan los botones), así que estos métodos
    // pueden tocar componentes Swing directamente.

    @Override
    public void onTurnStarted(int turnNumber, boolean playerAttacks) {
        log("");
        log("--- Turno " + turnNumber + ": ataca " + (playerAttacks ? "el Jugador" : "la Máquina") + " ---");
        if (playerAttacks) {
            positionCombo.setSelectedItem(Position.ATAQUE);
            positionCombo.setEnabled(false);
            turnLabel.setText("Turno " + turnNumber + ": te toca ATACAR. Elige una carta.");
        } else {
            positionCombo.setEnabled(true);
            turnLabel.setText("Turno " + turnNumber + ": la máquina ataca. Elige carta y posición (Ataque o Defensa).");
        }
    }

    @Override
    public void onCardsRevealed(Card playerCard, Position playerPosition, Card aiCard, Position aiPosition) {
        fieldPlayer.showCard(playerCard, images.get(playerCard.getId()));
        fieldPlayer.setFooter("En " + playerPosition);
        fieldAi.showCard(aiCard, images.get(aiCard.getId()));
        fieldAi.setFooter("En " + aiPosition);
        int left = duel.getAiCardsLeft();
        for (int i = 0; i < aiBackLabels.size(); i++) {
            aiBackLabels.get(i).setIcon(i < left ? SMALL_BACK : null);
        }
    }

    @Override
    public void onTurn(String playerCard, String aiCard, String winner) {
        log("Jugador juega: " + playerCard);
        log("Máquina juega: " + aiCard);
        log(Duel.DRAW.equals(winner) ? "Resultado: empate, nadie suma punto." : "Gana el turno: " + winner);
    }

    @Override
    public void onScoreChanged(int playerScore, int aiScore) {
        scoreLabel.setText("Jugador " + playerScore + " - " + aiScore + " Máquina");
        log("Marcador: Jugador " + playerScore + " - " + aiScore + " Máquina");
    }

    @Override
    public void onDuelEnded(String winner) {
        setState(State.FINISHED);
        String message;
        if (Duel.PLAYER.equals(winner)) {
            message = "¡Ganaste el duelo!";
        } else if (Duel.AI.equals(winner)) {
            message = "La máquina ganó el duelo.";
        } else {
            message = "El duelo terminó en empate.";
        }
        log("");
        log("=== FIN DEL DUELO: " + message + " ===");
        turnLabel.setText(message + " Pulsa \"Nuevo duelo\" para jugar otra vez.");
        // Se muestra después de que Swing pinte el último turno
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, message, "Resultado del duelo", JOptionPane.INFORMATION_MESSAGE));
    }

    // ------------------------------------------------------------------ utilidades

    private void setState(State newState) {
        state = newState;
        switch (newState) {
            case LOADING:
                startButton.setText("Cargando cartas...");
                startButton.setEnabled(false);
                break;
            case LOAD_FAILED:
                startButton.setText("Reintentar carga");
                startButton.setEnabled(true);
                break;
            case READY:
                startButton.setText("Iniciar duelo");
                startButton.setEnabled(true);
                break;
            case PLAYING:
                startButton.setText("Duelo en curso");
                startButton.setEnabled(false);
                break;
            case FINISHED:
                startButton.setText("Nuevo duelo");
                startButton.setEnabled(true);
                break;
        }
        boolean playing = newState == State.PLAYING;
        chooseButton.setEnabled(false); // se habilita al seleccionar una carta
        positionCombo.setEnabled(playing);
        for (CardPanel p : playerCardPanels) {
            p.setCursor(playing ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            if (!playing) {
                p.setSelected(false);
            }
        }
    }

    private void log(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static javax.swing.border.Border titled(String title) {
        javax.swing.border.TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(110, 115, 130)), title);
        border.setTitleColor(new Color(220, 225, 235));
        return border;
    }

    private static JPanel labeled(String text, CardPanel card) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.add(label, BorderLayout.NORTH);
        panel.add(card, BorderLayout.CENTER);
        return panel;
    }
}
