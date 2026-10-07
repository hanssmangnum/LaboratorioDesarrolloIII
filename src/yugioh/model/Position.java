package yugioh.model;

/**
 * Posición en la que se juega una carta durante un turno.
 */
public enum Position {
    ATAQUE("Ataque"),
    DEFENSA("Defensa");

    private final String label;

    Position(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
