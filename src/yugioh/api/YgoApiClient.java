package yugioh.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import yugioh.model.Card;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Cliente de la API REST YGOProDeck.
 * <p>
 * Todas las llamadas son bloqueantes: deben ejecutarse fuera del hilo de
 * la interfaz (por ejemplo dentro de un {@link javax.swing.SwingWorker}).
 */
public class YgoApiClient {

    private static final String RANDOM_CARD_URL = "https://db.ygoprodeck.com/api/v7/randomcard.php";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** Máximo de peticiones para conseguir un monstruo válido antes de rendirse. */
    private static final int MAX_ATTEMPTS = 20;

    private final HttpClient http;

    public YgoApiClient() {
        // randomcard.php responde con un 301 hacia cardinfo.php, por eso se siguen las redirecciones
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Pide cartas al azar hasta obtener una de tipo Monster con ATK y DEF numéricos.
     * Si la carta es mágica, trampa o Link (no tiene DEF), se vuelve a solicitar.
     */
    public Card fetchRandomMonster() throws ApiException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String body = send(RANDOM_CARD_URL, HttpResponse.BodyHandlers.ofString());
            Card card = parseMonster(body);
            if (card != null) {
                return card;
            }
        }
        throw new ApiException("No se pudo cargar la carta: no se obtuvo un monstruo válido tras "
                + MAX_ATTEMPTS + " intentos.");
    }

    /** Descarga la imagen de una carta desde la URL entregada por la API. */
    public BufferedImage downloadImage(String url) throws ApiException {
        if (url == null || url.isEmpty()) {
            throw new ApiException("La carta no tiene imagen.");
        }
        byte[] bytes = send(url, HttpResponse.BodyHandlers.ofByteArray());
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ApiException("Formato de imagen no soportado.");
            }
            return image;
        } catch (IOException e) {
            throw new ApiException("No se pudo leer la imagen.", e);
        }
    }

    /**
     * Convierte la respuesta JSON en una {@link Card}. Devuelve {@code null}
     * si la carta no sirve para el duelo (no es Monster o le falta ATK/DEF).
     * La API puede devolver la carta envuelta en {"data":[...]} o directamente.
     */
    static Card parseMonster(String json) throws ApiException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.has("data") ? root.getJSONArray("data").getJSONObject(0) : root;

            String type = data.optString("type", "");
            int atk = data.optInt("atk", -1);
            int def = data.optInt("def", -1);
            if (!type.contains("Monster") || atk < 0 || def < 0) {
                return null;
            }

            String imageUrl = "";
            JSONArray images = data.optJSONArray("card_images");
            if (images != null && !images.isEmpty()) {
                JSONObject image = images.getJSONObject(0);
                imageUrl = image.optString("image_url_small", image.optString("image_url", ""));
            }

            return new Card(data.getInt("id"), data.getString("name"), type, atk, def, imageUrl);
        } catch (JSONException e) {
            throw new ApiException("No se pudo cargar la carta: respuesta JSON inválida.", e);
        }
    }

    /** Ejecuta un GET y traduce los fallos a mensajes entendibles para el usuario. */
    private <T> T send(String url, HttpResponse.BodyHandler<T> handler) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "YuGiOhDuelLite/1.0")
                .GET()
                .build();
        HttpResponse<T> response;
        try {
            response = http.send(request, handler);
        } catch (HttpTimeoutException e) {
            throw new ApiException("Error de red: la API tardó demasiado en responder.", e);
        } catch (IOException e) {
            throw new ApiException("Error de red: no se pudo conectar con YGOProDeck.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("La petición fue cancelada.", e);
        }
        if (response.statusCode() != 200) {
            throw new ApiException("No se pudo cargar la carta (HTTP " + response.statusCode() + ").");
        }
        return response.body();
    }
}
