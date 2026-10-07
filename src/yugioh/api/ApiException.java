package yugioh.api;

/**
 * Error al consultar la API YGOProDeck. El mensaje está pensado para
 * mostrarse directamente al usuario ("error de red", "no se pudo cargar la carta"...).
 */
public class ApiException extends Exception {

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
