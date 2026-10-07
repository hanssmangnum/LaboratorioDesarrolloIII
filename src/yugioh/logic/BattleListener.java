package yugioh.logic;

import yugioh.model.Card;
import yugioh.model.Position;

/**
 * Eventos que emite un {@link Duel}. Permite que la interfaz reaccione
 * a la partida sin que la lógica conozca nada de Swing.
 */
public interface BattleListener {

    /** Se resolvió un turno. {@code winner} es "Jugador", "Máquina" o "Empate". */
    void onTurn(String playerCard, String aiCard, String winner);

    /** Cambió el marcador de rondas. */
    void onScoreChanged(int playerScore, int aiScore);

    /** Terminó el duelo. {@code winner} es "Jugador", "Máquina" o "Empate". */
    void onDuelEnded(String winner);

    /** Comienza un turno; indica quién ataca. Opcional. */
    default void onTurnStarted(int turnNumber, boolean playerAttacks) {
    }

    /** Se revelan las cartas jugadas en el turno, antes de resolverlo. Opcional. */
    default void onCardsRevealed(Card playerCard, Position playerPosition,
                                 Card aiCard, Position aiPosition) {
    }
}
