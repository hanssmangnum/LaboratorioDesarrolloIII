package yugioh.ui;

import yugioh.api.ApiException;
import yugioh.api.YgoApiClient;
import yugioh.logic.BattleListener;
import yugioh.logic.Duel;
import yugioh.model.Card;
import yugioh.model.Position;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Cursor;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ExecutionException;

/**
 * Ventana principal del duelo.
 * <p>
 * El diseño visual está en {@code DuelFrame.form} (GUI Designer de IntelliJ); por eso los
 * componentes enlazados al formulario no se crean con {@code new}.
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
    private static final int FIELD_CARD_WIDTH = 80;
    private static final int FIELD_CARD_HEIGHT = 117;
    /** Pausa entre mensajes del duelo en el log, para que se puedan leer uno a uno. */
    private static final int LOG_DELAY_MS = 1000;

    private final YgoApiClient api = new YgoApiClient();
    private final Random random = new Random();

    // Componentes del formulario (los crea el GUI Designer a partir de DuelFrame.form)
    private JPanel mainPanel;
    private JLabel scoreLabel;
    private JLabel aiBack1;
    private JLabel aiBack2;
    private JLabel aiBack3;
    private CardPanel playerCard1;
    private CardPanel playerCard2;
    private CardPanel playerCard3;
    private CardPanel fieldPlayer;
    private CardPanel fieldAi;
    private JTextPane logArea;
    private JButton startButton;
    private JButton changeDeckButton;
    private JButton attackButton;
    private JButton defendButton;

    private final List<CardPanel> playerCardPanels;
    private final List<JLabel> aiBackLabels;

    private List<Card> playerCards = new ArrayList<>();
    private List<Card> aiCards = new ArrayList<>();
    private Map<Integer, ImageIcon> images = new HashMap<>();
    private Duel duel;
    private State state;

    /** Mensaje pendiente del log; {@code paced} = esperar LOG_DELAY_MS antes del siguiente. */
    private record LogEntry(String text, boolean paced) { }

    private final Queue<LogEntry> logQueue = new ArrayDeque<>();
    private final Timer logTimer = new Timer(LOG_DELAY_MS, e -> showNextLog());
    /** Resultado del duelo que se muestra en un diálogo cuando el log termina de escribirse. */
    private String pendingResult;

    public DuelFrame() {
        super("Yu-Gi-Oh! Duel Lite");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setContentPane(mainPanel);
        playerCardPanels = Arrays.asList(playerCard1, playerCard2, playerCard3);
        aiBackLabels = Arrays.asList(aiBack1, aiBack2, aiBack3);
        // Las cartas del combate van más pequeñas para que la ventana quepa en pantallas bajas
        fieldAi.setImageSize(FIELD_CARD_WIDTH, FIELD_CARD_HEIGHT);
        fieldPlayer.setImageSize(FIELD_CARD_WIDTH, FIELD_CARD_HEIGHT);
        logTimer.setRepeats(false);
        registerListeners();
        pack();
        // Nunca más grande que el área útil de la pantalla (sin la barra de tareas)
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(getWidth(), screen.width), Math.min(getHeight(), screen.height));
        setLocationRelativeTo(null);
        loadCards();
    }

    // ------------------------------------------------------------------ eventos de la UI

    private void registerListeners() {
        // "Iniciar duelo": empieza el duelo; al terminar uno, juega la revancha con las mismas cartas
        startButton.addActionListener(e -> {
            if (state == State.FINISHED) {
                showHands();
            }
            startDuel();
        });

        // "Cambiar mazo": pide 6 cartas nuevas a la API (también sirve para reintentar si falló)
        changeDeckButton.addActionListener(e -> loadCards());

        // "Atacar" / "Defender": juegan la carta seleccionada en esa posición
        attackButton.addActionListener(e -> playSelectedCard(Position.ATAQUE));
        defendButton.addActionListener(e -> playSelectedCard(Position.DEFENSA));

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
        showScore(0, 0);
        for (CardPanel panel : playerCardPanels) {
            panel.clear("Cargando...");
        }
        for (JLabel back : aiBackLabels) {
            back.setIcon(null);
        }
        fieldPlayer.clear(" ");
        fieldAi.clear(" ");
        logNow("**=== Cargando cartas desde la API ===**");

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
                            + (owner.equals("Jugador") ? ": **" + card + "**" : ""));
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
                messages.forEach(DuelFrame.this::logNow);
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
        showHands();
        logNow("Cartas listas. Pulsa **Iniciar duelo**.");
        setState(State.READY);
    }

    /** Muestra las 3 cartas del jugador y los reversos de la máquina, y limpia el campo. */
    private void showHands() {
        for (int i = 0; i < playerCardPanels.size(); i++) {
            Card card = playerCards.get(i);
            playerCardPanels.get(i).showCard(card, images.get(card.getId()));
        }
        for (JLabel back : aiBackLabels) {
            back.setIcon(SMALL_BACK);
        }
        fieldPlayer.clear(" ");
        fieldAi.clear(" ");
        showScore(0, 0);
    }

    private void onLoadFailed(String message) {
        logNow("**ERROR:** " + message);
        logNow("Pulsa **Cambiar mazo** para reintentar.");
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
        log("**=== ¡Comienza el duelo! Primero en ganar " + Duel.ROUNDS_TO_WIN + " rondas ===**");
        duel.start();
    }

    private void selectCard(CardPanel panel) {
        // Mientras el log sigue escribiendo el turno anterior no se puede elegir carta
        if (state != State.PLAYING || isLogBusy() || panel.isUsed() || panel.getCard() == null) {
            return;
        }
        for (CardPanel p : playerCardPanels) {
            p.setSelected(p == panel);
        }
        // Tu carta pasa al combate boca arriba; la de la máquina queda boca abajo hasta el combate
        Card card = panel.getCard();
        fieldPlayer.showCard(card, images.get(card.getId()));
        fieldPlayer.setFooter("Tu elección");
        fieldAi.showHidden();
        fieldAi.setFooter("Boca abajo");
        updateButtons();
    }

    /**
     * Habilita los botones según el estado. Mientras el log escribe mensajes todos esperan.
     * Atacar necesita una carta elegida; Defender además solo vale cuando ataca la máquina,
     * porque el atacante siempre juega en Ataque.
     */
    private void updateButtons() {
        boolean free = !isLogBusy();
        boolean cardSelected = state == State.PLAYING
                && playerCardPanels.stream().anyMatch(CardPanel::isSelected);
        startButton.setEnabled(free && (state == State.READY || state == State.FINISHED));
        changeDeckButton.setEnabled(free && state != State.LOADING && state != State.PLAYING);
        attackButton.setEnabled(free && cardSelected);
        defendButton.setEnabled(free && cardSelected && !duel.isPlayerAttacking());
    }

    private void playSelectedCard(Position position) {
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
        updateButtons();
        duel.playTurn(selected.getCard(), position);
    }

    // ------------------------------------------------------------------ BattleListener
    // Duel se ejecuta en el EDT (lo invocan los botones), así que estos métodos
    // pueden tocar componentes Swing directamente.

    @Override
    public void onTurnStarted(int turnNumber, boolean playerAttacks) {
        log("");
        log("**--- Turno " + turnNumber + ": ataca " + (playerAttacks ? "el Jugador" : "la Máquina") + " ---**");
        if (playerAttacks) {
            log("» Te toca **ATACAR**: elige una carta y pulsa **Atacar**.");
        } else {
            log("» Ataca **la máquina**: elige una carta y pulsa **Atacar** o **Defender**.");
        }
        updateButtons();
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
        log("Jugador juega: **" + playerCard + "**");
        log("Máquina juega: **" + aiCard + "**");
        log(Duel.DRAW.equals(winner) ? "Resultado: **empate**, nadie suma punto." : "Gana el turno: **" + winner + "**");
    }

    @Override
    public void onScoreChanged(int playerScore, int aiScore) {
        showScore(playerScore, aiScore);
        log("Marcador: **Jugador " + playerScore + " - " + aiScore + " Máquina**");
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
        log("**=== FIN DEL DUELO: " + message + " ===**");
        log("**Iniciar duelo** = revancha, **Cambiar mazo** = cartas nuevas.");
        // El diálogo sale cuando el log termina de escribir el último turno (ver onLogIdle)
        pendingResult = message;
    }

    // ------------------------------------------------------------------ utilidades

    private void setState(State newState) {
        state = newState;
        boolean playing = newState == State.PLAYING;
        for (CardPanel p : playerCardPanels) {
            p.setCursor(playing ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            if (!playing) {
                p.setSelected(false);
            }
        }
        updateButtons();
    }

    /** Marcador entre las dos cartas del combate, con el formato del diseño ("Jugador 0 VS 0 Máquina"). */
    private void showScore(int playerScore, int aiScore) {
        scoreLabel.setText("Jugador " + playerScore + " VS " + aiScore + " Máquina");
    }

    // ------------------------------------------------------------------ log con pausas y negrilla

    /** Mensaje del duelo: se escribe tras los anteriores y hace esperar 1 s al siguiente. */
    private void log(String message) {
        enqueueLog(message, !message.isEmpty());
    }

    /** Mensaje sin pausa (progreso de carga, errores). */
    private void logNow(String message) {
        enqueueLog(message, false);
    }

    private void enqueueLog(String message, boolean paced) {
        logQueue.add(new LogEntry(message, paced));
        if (!logTimer.isRunning()) {
            showNextLog();
        }
        updateButtons();
    }

    /** Escribe los mensajes pendientes; se detiene 1 s después de cada mensaje con pausa. */
    private void showNextLog() {
        LogEntry entry;
        while ((entry = logQueue.poll()) != null) {
            appendLog(entry.text());
            if (entry.paced()) {
                logTimer.restart();
                return;
            }
        }
        onLogIdle();
    }

    private boolean isLogBusy() {
        return logTimer.isRunning() || !logQueue.isEmpty();
    }

    /** El log terminó de escribir: se reactivan los botones y, si el duelo acabó, se anuncia. */
    private void onLogIdle() {
        updateButtons();
        if (pendingResult != null) {
            String message = pendingResult;
            pendingResult = null;
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, message, "Resultado del duelo", JOptionPane.INFORMATION_MESSAGE));
        }
    }

    /** Agrega una línea al log; el texto entre ** y ** se escribe en negrilla. */
    private void appendLog(String message) {
        StyledDocument doc = logArea.getStyledDocument();
        SimpleAttributeSet normal = new SimpleAttributeSet();
        SimpleAttributeSet bold = new SimpleAttributeSet();
        StyleConstants.setBold(bold, true);
        String[] parts = message.split("\\*\\*", -1);
        try {
            for (int i = 0; i < parts.length; i++) {
                doc.insertString(doc.getLength(), parts[i], i % 2 == 1 ? bold : normal);
            }
            doc.insertString(doc.getLength(), "\n", normal);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e); // no ocurre: siempre se inserta al final
        }
        logArea.setCaretPosition(doc.getLength());
    }
}
