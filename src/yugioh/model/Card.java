package yugioh.model;

/**
 * Modelo inmutable de una carta Monster obtenida desde la API YGOProDeck.
 * Solo guarda los datos que el duelo y la interfaz necesitan.
 */
public class Card {

    private final int id;
    private final String name;
    private final String type;
    private final int atk;
    private final int def;
    private final String imageUrl;

    public Card(int id, String name, String type, int atk, int def, String imageUrl) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.atk = atk;
        this.def = def;
        this.imageUrl = imageUrl;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public int getAtk() {
        return atk;
    }

    public int getDef() {
        return def;
    }

    /** URL de la imagen oficial (versión pequeña) entregada por la API. */
    public String getImageUrl() {
        return imageUrl;
    }

    @Override
    public String toString() {
        return name + " (ATK " + atk + " / DEF " + def + ")";
    }
}
