package yugioh.logic;

import yugioh.model.Card;
import yugioh.model.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Reglas del duelo simplificado.
 * <ul>
 *   <li>Cada jugador tiene 3 cartas y cada carta se usa una sola vez.</li>
 *   <li>El primer atacante se sortea; después el ataque se alterna cada turno.</li>
 *   <li>El atacante juega en Ataque; el defensor elige Ataque o Defensa
 *       (la máquina lo elige al azar).</li>
 *   <li>Ataque vs Ataque: gana el mayor ATK. Ataque vs Defensa: ATK del atacante
 *       contra DEF del defensor. Si los valores son iguales, el turno es empate.</li>
 *   <li>Gana el duelo quien llegue primero a 2 rondas. Si se acaban las cartas
 *       antes (por empates), gana quien tenga más rondas.</li>
 * </ul>
 * Los resultados se comunican a través de {@link BattleListener}.
 */
public class Duel {

    public static final String PLAYER = "Jugador";
    public static final String AI = "Máquina";
    public static final String DRAW = "Empate";

    public static final int CARDS_PER_PLAYER = 3;
    public static final int ROUNDS_TO_WIN = 2;

    private final List<Card> playerHand;
    private final List<Card> aiHand;
    private final Random random;
    private final List<BattleListener> listeners = new ArrayList<>();

    private int playerScore;
    private int aiScore;
    private int turnNumber;
    private boolean playerAttacks;
    private boolean started;
    private boolean finished;

    public Duel(List<Card> playerCards, List<Card> aiCards, Random random) {
        if (playerCards.size() != CARDS_PER_PLAYER || aiCards.size() != CARDS_PER_PLAYER) {
            throw new IllegalArgumentException("Ambos jugadores deben tener "
                    + CARDS_PER_PLAYER + " cartas cargadas para iniciar el duelo.");
        }
        this.playerHand = new ArrayList<>(playerCards);
        this.aiHand = new ArrayList<>(aiCards);
        this.random = random;
    }

    public void addBattleListener(BattleListener listener) {
        listeners.add(listener);
    }

    /** Sortea quién ataca primero y abre el primer turno. */
    public void start() {
        if (started) {
            throw new IllegalStateException("El duelo ya comenzó.");
        }
        started = true;
        turnNumber = 1;
        playerAttacks = random.nextBoolean();
        for (BattleListener l : listeners) {
            l.onTurnStarted(turnNumber, playerAttacks);
        }
    }

    /**
     * Juega un turno: el jugador usa {@code playerCard} y la máquina elige una carta al azar.
     *
     * @param requestedPosition posición que pidió el jugador; se ignora cuando le toca
     *                          atacar, porque el atacante siempre va en Ataque.
     */
    public void playTurn(Card playerCard, Position requestedPosition) {
        if (!started || finished) {
            throw new IllegalStateException("No hay un duelo en curso.");
        }
        if (!playerHand.contains(playerCard)) {
            throw new IllegalArgumentException("La carta ya fue usada o no pertenece al jugador.");
        }

        Card aiCard = aiHand.get(random.nextInt(aiHand.size()));
        Position playerPosition = playerAttacks ? Position.ATAQUE : requestedPosition;
        Position aiPosition = playerAttacks ? randomPosition() : Position.ATAQUE;

        playerHand.remove(playerCard);
        aiHand.remove(aiCard);

        for (BattleListener l : listeners) {
            l.onCardsRevealed(playerCard, playerPosition, aiCard, aiPosition);
        }

        String winner = resolve(playerCard, playerPosition, aiCard, aiPosition);
        if (PLAYER.equals(winner)) {
            playerScore++;
        } else if (AI.equals(winner)) {
            aiScore++;
        }

        String playerDesc = describe(playerCard, playerPosition);
        String aiDesc = describe(aiCard, aiPosition);
        for (BattleListener l : listeners) {
            l.onTurn(playerDesc, aiDesc, winner);
            l.onScoreChanged(playerScore, aiScore);
        }

        if (playerScore >= ROUNDS_TO_WIN) {
            finish(PLAYER);
        } else if (aiScore >= ROUNDS_TO_WIN) {
            finish(AI);
        } else if (playerHand.isEmpty()) {
            // Se acabaron las cartas sin que nadie llegara a 2 (hubo empates)
            finish(playerScore > aiScore ? PLAYER : aiScore > playerScore ? AI : DRAW);
        } else {
            turnNumber++;
            playerAttacks = !playerAttacks;
            for (BattleListener l : listeners) {
                l.onTurnStarted(turnNumber, playerAttacks);
            }
        }
    }

    /**
     * Compara las dos cartas según sus posiciones y devuelve el ganador del turno.
     * Si ninguna está en Ataque no hay batalla y el turno es empate.
     */
    static String resolve(Card playerCard, Position playerPosition, Card aiCard, Position aiPosition) {
        int playerValue;
        int aiValue;
        if (playerPosition == Position.ATAQUE && aiPosition == Position.ATAQUE) {
            playerValue = playerCard.getAtk();
            aiValue = aiCard.getAtk();
        } else if (playerPosition == Position.ATAQUE) {
            playerValue = playerCard.getAtk();
            aiValue = aiCard.getDef();
        } else if (aiPosition == Position.ATAQUE) {
            playerValue = playerCard.getDef();
            aiValue = aiCard.getAtk();
        } else {
            return DRAW;
        }
        if (playerValue > aiValue) {
            return PLAYER;
        }
        if (aiValue > playerValue) {
            return AI;
        }
        return DRAW;
    }

    private static String describe(Card card, Position position) {
        String stat = position == Position.ATAQUE ? "ATK " + card.getAtk() : "DEF " + card.getDef();
        return card.getName() + " [" + position + ", " + stat + "]";
    }

    private Position randomPosition() {
        return random.nextBoolean() ? Position.ATAQUE : Position.DEFENSA;
    }

    private void finish(String winner) {
        finished = true;
        for (BattleListener l : listeners) {
            l.onDuelEnded(winner);
        }
    }

    public boolean isPlayerAttacking() {
        return playerAttacks;
    }

    public boolean isFinished() {
        return finished;
    }

    public int getPlayerScore() {
        return playerScore;
    }

    public int getAiScore() {
        return aiScore;
    }

    public List<Card> getPlayerHand() {
        return Collections.unmodifiableList(playerHand);
    }

    public int getAiCardsLeft() {
        return aiHand.size();
    }
}
