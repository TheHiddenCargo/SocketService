package arsw.tamaltolimense.SocketServer;
import com.corundumstudio.socketio.*;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DataListener;
import com.corundumstudio.socketio.listener.DisconnectListener;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.security.SecureRandom;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class LobbySocketService {
    private static final Logger logger = LoggerFactory.getLogger(LobbySocketService.class);

    // API URLs
    private static final String LOBBIES_API_URL = "https://thehiddencargo1.azure-api.net/lobbies/lobbies";
    private static final String BID_SERVICE_URL = "https://thehiddencargo1.azure-api.net/bids";
    private static final String API_KEY = System.getenv("API_KEY");

    private SocketIOServer server;
    private final Map<String, String> sessionToNickname = new HashMap<>();
    private final Map<String, String> sessionToLobby = new HashMap<>();

    // Mapas para gestionar partidas
    private final Map<String, GameState> activeGames = new HashMap<>();
    private final Map<String, List<PlayerState>> gamePlayers = new HashMap<>();
    private final Map<String, Queue<ContainerInfo>> gameContainers = new HashMap<>();
    private final Map<String, Timer> gameTimers = new HashMap<>();
    private final Map<String, Set<String>> playersReadyForNextRound = new HashMap<>();
    private final Map<String, Integer> playerBalances = new HashMap<>();

    // RestTemplate para comunicación con servicios externos
    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        try {
            Configuration config = new Configuration();
            config.setHostname("0.0.0.0");
            config.setPort(443);

            // Configuración correcta para Socket.IO
            config.setContext("/socket.io");
            config.setOrigin("*");
            config.setAllowCustomRequests(true);
            config.setAuthorizationListener(data -> true);
            config.setTransports(new Transport[]{Transport.WEBSOCKET, Transport.POLLING});

            // Configuraciones adicionales
            config.setPingTimeout(60000);
            config.setPingInterval(25000);

            logger.info("Creando instancia de SocketIOServer");
            server = new SocketIOServer(config);

            // Configurar listeners para eventos de conexión y desconexión
            server.addConnectListener(onConnected());
            server.addDisconnectListener(onDisconnected());

            // Configurar listeners para eventos específicos del lobby
            server.addEventListener("joinLobby", JoinLobbyData.class, onJoinLobby());
            server.addEventListener("leaveLobby", LeaveLobbyData.class, onLeaveLobby());
            server.addEventListener("playerReady", PlayerReadyData.class, onPlayerReady());
            server.addEventListener("playerNotReady", PlayerNotReadyData.class, onPlayerNotReady());
            server.addEventListener("chatMessage", ChatMessageData.class, onChatMessage());
            server.addEventListener("readyForNextRound", ReadyForNextRoundData.class, onReadyForNextRound());
            server.addEventListener("updatePlayerBalance", PlayerBalanceData.class, onUpdatePlayerBalance());

            // Eventos del juego
            server.addEventListener("startGame", StartGameData.class, onStartGame());
            server.addEventListener("placeBid", PlaceBidData.class, onPlaceBid());
            server.addEventListener("leaveGame", LeaveGameData.class, onLeaveGame());

            logger.info("Iniciando servidor Socket.IO en puerto 443 con path /socket.io");
            server.start();
            logger.info("SocketIO Server iniciado en puerto 443 con path /socket.io");
        } catch (Exception e) {
            logger.error("Error al iniciar SocketIO Server", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (server != null) {
            logger.info("Deteniendo SocketIO Server");
            server.stop();
        }
    }

    // Método para crear headers con la API key
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Ocp-Apim-Subscription-Key", API_KEY);
        return headers;
    }

    // Métodos para interactuar con la API de Lobbies
    private Map<String, Object> getLobby(String lobbyName) {
        try {
            String url = LOBBIES_API_URL + "/" + lobbyName;
            HttpEntity<String> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            return null;
        } catch (Exception e) {
            logger.error("Error al obtener lobby {}: {}", lobbyName, e.getMessage());
            return null;
        }
    }

    private boolean addPlayerToLobby(String lobbyName, String nickname) {
        try {
            String url = LOBBIES_API_URL + "/" + lobbyName + "/agregarJugador?nickname=" + nickname;
            HttpEntity<String> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error al agregar jugador {} al lobby {}: {}",
                    nickname, lobbyName, e.getMessage());
            return false;
        }
    }

    private boolean removePlayerFromLobby(String lobbyName, String nickname) {
        try {
            String url = LOBBIES_API_URL + "/" + lobbyName + "/quitarJugador?nickname=" + nickname;
            HttpEntity<String> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error al quitar jugador {} del lobby {}: {}",
                    nickname, lobbyName, e.getMessage());
            return false;
        }
    }

    private boolean markPlayerAsReady(String lobbyName) {
        try {
            String url = LOBBIES_API_URL + "/" + lobbyName + "/agregarListo";
            HttpEntity<String> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error al marcar jugador como listo en lobby {}: {}",
                    lobbyName, e.getMessage());
            return false;
        }
    }

    private boolean markPlayerAsNotReady(String lobbyName) {
        try {
            String url = LOBBIES_API_URL + "/" + lobbyName + "/quitarListo";
            HttpEntity<String> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Error al quitar jugador listo en lobby {}: {}",
                    lobbyName, e.getMessage());
            return false;
        }
    }

    private ConnectListener onConnected() {
        return client -> {
            logger.info("Cliente conectado: {}", client.getSessionId());
        };
    }

    private DisconnectListener onDisconnected() {
        return client -> {
            String sessionId = client.getSessionId().toString();
            String nickname = sessionToNickname.get(sessionId);
            String lobbyName = sessionToLobby.get(sessionId);

            logger.info("Cliente desconectado: {}. Nickname: {}, Lobby: {}",
                    sessionId, nickname, lobbyName);

            if (nickname != null && lobbyName != null) {
                // Obtener el estado actual del lobby
                Map<String, Object> lobby = getLobby(lobbyName);

                if (lobby != null) {
                    // Quitar al jugador del lobby usando la API
                    removePlayerFromLobby(lobbyName, nickname);

                    // Notificar a todos en la sala que el jugador se desconectó
                    server.getRoomOperations(lobbyName).sendEvent("playerLeft", new PlayerLeftData(nickname));
                    logger.info("Jugador {} removido del lobby {} por desconexión", nickname, lobbyName);

                    // Si hay una partida activa, manejar la salida del jugador
                    if (activeGames.containsKey(lobbyName)) {
                        handlePlayerLeaveGame(lobbyName, nickname);
                    }
                }
            }

            // Limpiar los mapas
            sessionToNickname.remove(sessionId);
            sessionToLobby.remove(sessionId);
        };
    }

    // Método actualizado para actualizar el balance en el servicio externo
    private void updateUserBalance(String nickname, int profit) {
        try {
            // Crear los datos para la petición
            Map<String, Object> requestData = new HashMap<>();
            requestData.put("username", nickname);
            requestData.put("amount", profit);

            // Configurar los headers con la clave de suscripción correcta
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Ocp-Apim-Subscription-Key", API_KEY);

            // Crear la entidad HTTP con el cuerpo JSON
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestData, headers);

            // URL del servicio
            String apiUrl = "https://thehiddencargo1.azure-api.net/creation/polling/users/offer/username";

            // Hacer la petición POST
            logger.info("Enviando actualización de balance para usuario {}: profit={}", nickname, profit);
            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );

            // Verificar respuesta
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseData = response.getBody();
                if (responseData != null && responseData.containsKey("userBalance")) {
                    int newBalance = ((Number) responseData.get("userBalance")).intValue();
                    logger.info("Balance actualizado para usuario {}: nuevo balance={}", nickname, newBalance);

                    // Actualizar el balance en el juego con el nuevo valor del servicio
                    // Esto es importante para mantener sincronizado el balance del juego con el del sistema
                    updatePlayerBalanceFromService(nickname, newBalance);
                } else {
                    logger.warn("La respuesta del servicio no contiene el campo userBalance: {}", responseData);
                }
            } else {
                logger.error("Error al actualizar balance para usuario {}: {}", nickname, response.getStatusCode());
            }
        }catch (NullPointerException e) {
            logger.error("Null pointerException {}: {}", nickname, e.getMessage(), e);

        } catch (Exception e) {
            logger.error("Error al enviar actualización de balance para usuario {}: {}", nickname, e.getMessage(), e);
        }
    }

    private void updatePlayerBalanceFromService(String nickname, int newBalance) {
        // Buscar al jugador en todos los juegos activos
        for (Map.Entry<String, List<PlayerState>> entry : gamePlayers.entrySet()) {
            String lobbyName = entry.getKey();
            List<PlayerState> players = entry.getValue();

            for (PlayerState player : players) {
                if (player.getNickname().equals(nickname)) {
                    // Actualizamos el balance con el valor del servicio
                    player.setBalance(newBalance);

                    // Notificar a todos los clientes sobre el balance actualizado
                    PlayerUpdateData updateData = new PlayerUpdateData();
                    updateData.setNickname(nickname);
                    updateData.setBalance(newBalance);
                    updateData.setScore(player.getScore());

                    server.getRoomOperations(lobbyName).sendEvent("playerUpdate", updateData);
                    logger.info("Balance de jugador {} actualizado con valor del servicio: {}", nickname, newBalance);

                    // Actualizamos también el mapa de balances para este jugador
                    playerBalances.put(nickname, newBalance);
                    break;
                }
            }
        }
    }

    private DataListener<PlayerBalanceData> onUpdatePlayerBalance() {
        return (client, data, ackRequest) -> {
            String nickname = data.getNickname();
            String lobbyName = data.getLobbyName();
            int initialBalance = data.getInitialBalance();

            logger.info("Recibido balance inicial para jugador {}: {}", nickname, initialBalance);

            // Almacenar el balance del jugador
            playerBalances.put(nickname, initialBalance);

            // Si hay un juego activo, actualizar el balance del jugador
            if (activeGames.containsKey(lobbyName)) {
                List<PlayerState> players = gamePlayers.get(lobbyName);
                if (players != null) {
                    for (PlayerState player : players) {
                        if (player.getNickname().equals(nickname)) {
                            player.setBalance(initialBalance);
                            logger.info("Balance actualizado para jugador {} en juego activo: {}",
                                    nickname, initialBalance);
                            break;
                        }
                    }
                }
            }

            if (ackRequest.isAckRequested()) {
                ackRequest.sendAckData("Balance actualizado correctamente");
            }
        };
    }

    private DataListener<JoinLobbyData> onJoinLobby() {
        return (client, data, ackRequest) -> {
            logger.info("Jugador {} intenta unirse al lobby: {}", data.getNickname(), data.getLobbyName());

            // Guardar la información de sesión
            String sessionId = client.getSessionId().toString();
            sessionToNickname.put(sessionId, data.getNickname());
            sessionToLobby.put(sessionId, data.getLobbyName());

            // Usar la API para agregar el jugador
            boolean success = addPlayerToLobby(data.getLobbyName(), data.getNickname());

            if (success) {
                // Unir al cliente a la sala
                client.joinRoom(data.getLobbyName());

                // Notificar a todos en la sala que un jugador se unió
                server.getRoomOperations(data.getLobbyName()).sendEvent("playerJoined",
                        new PlayerJoinedData(data.getNickname()));

                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Te has unido al lobby: " + data.getLobbyName());
                }

                logger.info("Jugador {} unido exitosamente al lobby {}", data.getNickname(), data.getLobbyName());
            } else {
                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Error al unirse al lobby: " + data.getLobbyName());
                }
                // Limpiar información de sesión en caso de error
                sessionToNickname.remove(sessionId);
                sessionToLobby.remove(sessionId);
            }
        };
    }

    private DataListener<LeaveLobbyData> onLeaveLobby() {
        return (client, data, ackRequest) -> {
            String sessionId = client.getSessionId().toString();
            String nickname = sessionToNickname.get(sessionId);
            String lobbyName = data.getLobbyName();

            logger.info("Jugador {} intentando salir del lobby {}", nickname, lobbyName);

            if (nickname != null && lobbyName != null) {
                // Quitar al jugador de la sala
                client.leaveRoom(lobbyName);

                // Notificar a todos en la sala que el jugador se fue
                server.getRoomOperations(lobbyName).sendEvent("playerLeft",
                        new PlayerLeftData(nickname));

                // Usar la API para quitar al jugador del lobby
                boolean success = removePlayerFromLobby(lobbyName, nickname);
                if (success) {
                    logger.info("Jugador {} removido del lobby {}", nickname, lobbyName);
                }

                // Limpiar el mapa de lobby para esta sesión
                sessionToLobby.remove(sessionId);

                // Si hay una partida activa, manejar la salida del jugador
                if (activeGames.containsKey(lobbyName)) {
                    handlePlayerLeaveGame(lobbyName, nickname);
                }
            }

            if (ackRequest.isAckRequested()) {
                ackRequest.sendAckData("Has abandonado el lobby: " + lobbyName);
            }
        };
    }

    private DataListener<PlayerReadyData> onPlayerReady() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            String nickname = data.getNickname();

            logger.info("Jugador {} marcándose como listo en lobby {}", nickname, lobbyName);

            // Validar los datos recibidos
            if (lobbyName == null || lobbyName.isEmpty()) {
                logger.error("Nombre de lobby vacío o nulo en evento playerReady");
                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Error: Nombre de lobby inválido");
                }
                return;
            }

            if (nickname == null || nickname.isEmpty()) {
                logger.error("Nickname vacío o nulo en evento playerReady");
                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Error: Nickname inválido");
                }
                return;
            }

            // Obtener el lobby actual para diagnóstico
            Map<String, Object> lobby = getLobby(lobbyName);
            if (lobby == null) {
                logger.error("Lobby {} no encontrado al marcar jugador {} como listo", lobbyName, nickname);
                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Error: Lobby no encontrado");
                }
                return;
            }

            int jugadoresConectados = ((Number) lobby.get("jugadoresConectados")).intValue();
            int jugadoresListos = ((Number) lobby.get("jugadoresListos")).intValue();

            logger.info("Estado actual del lobby {}: jugadores conectados={}, jugadores listos={}",
                    lobbyName, jugadoresConectados, jugadoresListos);

            try {
                // Usar la API para marcar al jugador como listo
                boolean success = markPlayerAsReady(lobbyName);

                if (success) {
                    // Volver a obtener el lobby para ver el estado actualizado
                    Map<String, Object> lobbyActualizado = getLobby(lobbyName);
                    if (lobbyActualizado != null) {
                        int nuevosJugadoresListos = ((Number) lobbyActualizado.get("jugadoresListos")).intValue();
                        int nuevosJugadoresConectados = ((Number) lobbyActualizado.get("jugadoresConectados")).intValue();

                        logger.info("Jugador {} marcado como listo. Nuevo estado del lobby: jugadores listos={}",
                                nickname, nuevosJugadoresListos);

                        // Notificar a todos en la sala que el jugador está listo
                        server.getRoomOperations(lobbyName).sendEvent("playerReady",
                                new PlayerReadyData(nickname, lobbyName));

                        // Enviar confirmación al cliente
                        if (ackRequest.isAckRequested()) {
                            ackRequest.sendAckData("Te has marcado como listo");
                        }

                        // Comprobar si todos los jugadores están listos
                        if (nuevosJugadoresListos == nuevosJugadoresConectados) {
                            server.getRoomOperations(lobbyName).sendEvent("allPlayersReady", lobbyName);
                            logger.info("Todos los jugadores listos en lobby {}", lobbyName);
                        }
                    }
                } else {
                    logger.error("Error al marcar jugador {} como listo en lobby {}",
                            nickname, lobbyName);

                    if (ackRequest.isAckRequested()) {
                        ackRequest.sendAckData("Error al marcarte como listo");
                    }
                }
            } catch (Exception e) {
                logger.error("Excepción al marcar jugador {} como listo en lobby {}: {}",
                        nickname, lobbyName, e.getMessage(), e);

                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Error: " + e.getMessage());
                }
            }
        };
    }

    private DataListener<PlayerNotReadyData> onPlayerNotReady() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            String nickname = data.getNickname();

            logger.info("Jugador {} marcándose como no listo en lobby {}", nickname, lobbyName);

            // Usar la API para marcar al jugador como no listo
            boolean success = markPlayerAsNotReady(lobbyName);

            if (success) {
                // Notificar a todos en la sala que el jugador no está listo
                server.getRoomOperations(lobbyName).sendEvent("playerNotReady",
                        new PlayerNotReadyData(nickname, lobbyName));
            }
        };
    }

    private DataListener<ChatMessageData> onChatMessage() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            String nickname = data.getNickname();
            String message = data.getMessage();

            logger.info("Mensaje recibido de {} en lobby {}: {}", nickname, lobbyName, message);

            // Reenviar el mensaje a todos en la sala
            server.getRoomOperations(lobbyName).sendEvent("chatMessage", data);
        };
    }

    private DataListener<StartGameData> onStartGame() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            logger.info("Solicitud para iniciar juego en lobby: {}", lobbyName);

            // Obtener el lobby actual usando la API
            Map<String, Object> lobby = getLobby(lobbyName);

            if (lobby != null) {
                int jugadoresListos = ((Number) lobby.get("jugadoresListos")).intValue();
                int jugadoresConectados = ((Number) lobby.get("jugadoresConectados")).intValue();

                // Verificar si todos los jugadores están listos
                if (jugadoresListos < 2 || jugadoresListos != jugadoresConectados) {
                    sendErrorToClient(client, "No se puede iniciar el juego. Se necesitan al menos 2 jugadores y todos deben estar listos.", ackRequest);
                    return;
                }

                // Inicializar estado del juego
                GameState gameState = new GameState();
                gameState.setLobbyName(lobbyName);
                gameState.setCurrentRound(1);

                // Usar el nombre de campo correcto según tu modelo
                int rounds = ((Number) lobby.get("numeroDeRondas")).intValue();
                rounds = rounds > 0 ? rounds : 3;

                gameState.setTotalRounds(rounds);
                gameState.setStatus("STARTING");

                activeGames.put(lobbyName, gameState);

                // Inicializar jugadores
                List<PlayerState> players = new ArrayList<>();
                List<String> playersList = (List<String>) lobby.get("jugadores");

                if (playersList != null && !playersList.isEmpty()) {
                    for (String playerName : playersList) {
                        PlayerState player = new PlayerState();
                        player.setNickname(playerName);

                        // Buscar si existe un balance personalizado para este jugador
                        Integer customBalance = playerBalances.getOrDefault(playerName, null);
                        if (customBalance != null && customBalance > 0) {
                            player.setBalance(customBalance); // Usar el balance personalizado
                            logger.info("Usando balance personalizado para jugador {}: {}", playerName, customBalance);
                        } else {
                            player.setBalance(2000); // Usar el balance por defecto
                            logger.info("Usando balance por defecto para jugador {}: 2000", playerName);
                        }

                        player.setScore(0);
                        players.add(player);
                    }
                    gamePlayers.put(lobbyName, players);

                    // Generar contenedores para todas las rondas
                    Queue<ContainerInfo> containers = generateContainers(gameState.getTotalRounds() * 2);
                    gameContainers.put(lobbyName, containers);

                    try {
                        // Importante: Iniciar la primera ronda ANTES de enviar el evento de inicio de juego
                        // para asegurarnos de que el contenedor está configurado
                        ContainerInfo firstContainer = containers.peek();
                        if (firstContainer != null) {
                            // Solo asignar el contenedor pero NO consumirlo aún de la cola
                            // startNewRound() se encargará de extraerlo
                            gameState.setCurrentContainer(firstContainer);
                            logger.info("Contenedor asignado para primera ronda: {}", firstContainer.getId());
                        }

                        // Notificar que el juego ha comenzado con información de contenedor pre-asignada
                        GameStartedData gameStartedData = createGameStartedData(lobbyName, gameState, players);
                        server.getRoomOperations(lobbyName).sendEvent("gameStarted", gameStartedData);

                        // Log para verificar que el contenedor se envía correctamente
                        logger.info("Evento gameStarted enviado con contenedor: {}",
                                gameStartedData.getContainer() != null ?
                                        gameStartedData.getContainer().getId() : "null");

                        // Pequeña pausa antes de iniciar la primera ronda para que los clientes
                        // tengan tiempo de procesar el evento gameStarted
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                startNewRound(lobbyName);
                            }
                        }, 1000); // 1 segundo de espera

                        logger.info("Juego iniciado en lobby: {}", lobbyName);
                    } catch (Exception e) {
                        logger.error("Error al iniciar el juego en lobby {}: {}", lobbyName, e.getMessage(), e);
                        sendErrorToClient(client, "Error al iniciar el juego: " + e.getMessage(), ackRequest);
                    }
                } else {
                    sendErrorToClient(client, "No hay jugadores en el lobby: " + lobbyName, ackRequest);
                }
            } else {
                sendErrorToClient(client, "Lobby no encontrado: " + lobbyName, ackRequest);
            }
        };
    }
    // Método para crear el objeto de datos de inicio de juego
    private GameStartedData createGameStartedData(String lobbyName, GameState state, List<PlayerState> players) {
        GameStartedData data = new GameStartedData();

        // Obtener nombres de los jugadores
        List<String> playerNames = new ArrayList<>();
        for (PlayerState player : players) {
            playerNames.add(player.getNickname());
        }

        data.setPlayers(playerNames);

        // Verificar que el estado tenga un contenedor válido
        if (state.getCurrentContainer() == null) {
            logger.error("Error crítico: No hay contenedor disponible al iniciar el juego en lobby {}", lobbyName);

            // Crear un contenedor de emergencia si no hay uno disponible
            ContainerInfo emergencyContainer = new ContainerInfo();
            emergencyContainer.setId("emergency-container-" + UUID.randomUUID().toString().substring(0, 8));
            emergencyContainer.setType("Normal");
            emergencyContainer.setValue(300);

            // Asignar el contenedor de emergencia al estado y a los datos
            state.setCurrentContainer(emergencyContainer);
            data.setContainer(emergencyContainer);

            logger.info("Se ha creado un contenedor de emergencia para el lobby {}: {}",
                    lobbyName, emergencyContainer.getId());
        } else {
            data.setContainer(state.getCurrentContainer());
        }

        data.setInitialBid(100); // Apuesta inicial predeterminada
        data.setRound(state.getCurrentRound());
        data.setTotalRounds(state.getTotalRounds());

        // Log para diagnóstico
        logger.info("Datos de inicio de juego para lobby {}: ronda={}/{}, jugadores={}, contenedor={}",
                lobbyName, data.getRound(), data.getTotalRounds(),
                playerNames.size(), data.getContainer() != null ? data.getContainer().getId() : "null");

        return data;
    }

    private void startNewRound(String lobbyName) {
        if (!activeGames.containsKey(lobbyName)) {
            logger.warn("No se puede iniciar una nueva ronda. Juego no encontrado: {}", lobbyName);
            return;
        }

        GameState gameState = activeGames.get(lobbyName);
        Queue<ContainerInfo> containers = gameContainers.get(lobbyName);

        // Verificar si ya se llegó al límite de rondas
        if (gameState.getCurrentRound() > gameState.getTotalRounds()) {
            endGame(lobbyName);
            return;
        }

        // Obtener el siguiente contenedor
        ContainerInfo container = containers.poll();
        if (container == null) {
            logger.error("No hay más contenedores disponibles para el lobby {}", lobbyName);
            endGame(lobbyName);
            return;
        }

        // Actualizar el estado del juego
        gameState.setCurrentContainer(container);
        gameState.setStatus("BIDDING");
        gameState.setLastBidder(null);
        gameState.setCurrentBid(100); // Apuesta inicial

        try {
            int initialValue = 100;
            int realValue = container.getValue();

            // Crear una URL simplificada - MANTENIENDO LA URL ORIGINAL
            String apiUrl = BID_SERVICE_URL + "/bids/start";

            // Usar un Map<String, String> para los parámetros
            Map<String, String> params = new HashMap<>();
            params.put("container", container.getId());
            params.put("initialValue", String.valueOf(initialValue));
            params.put("realValue", String.valueOf(realValue));

            // Configurar los headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Ocp-Apim-Subscription-Key", API_KEY);

            // Crear la entidad HTTP con el cuerpo JSON
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(params, headers);

            // Hacer la petición POST con el cuerpo JSON
            logger.info("Iniciando subasta a {} con contenedor: {}, valor inicial: {}, valor real: {}",
                    apiUrl, container.getId(), initialValue, realValue);

            ResponseEntity<Object> response = restTemplate.postForEntity(
                    apiUrl,
                    requestEntity,
                    Object.class
            );

            logger.info("Respuesta del servicio de apuestas: {}", response.getStatusCode());

            // Enviar notificación de nueva ronda a todos los jugadores
            NewRoundData roundData = new NewRoundData();
            roundData.setRound(gameState.getCurrentRound());
            roundData.setTotalRounds(gameState.getTotalRounds());
            roundData.setContainer(container);
            roundData.setInitialBid(initialValue);

            // Enviar el evento varias veces para asegurar que todos lo reciban
            for (int attempt = 0; attempt < 3; attempt++) {
                server.getRoomOperations(lobbyName).sendEvent("newRound", roundData);
                logger.info("Intento {} - Enviando evento newRound para lobby {}, ronda {}/{}",
                        attempt + 1, lobbyName, gameState.getCurrentRound(), gameState.getTotalRounds());

                // Pequeña pausa entre intentos
                if (attempt < 2) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        // Re-interrumpir el hilo actual
                        Thread.currentThread().interrupt();
                        // Loguear el evento de interrupción
                        logger.warn("Hilo interrumpido mientras enviaba eventos a lobby {}", lobbyName);
                        break; // Salir del bucle si el hilo fue interrumpido
                    }
                }
            }

            // Enviar evento adicional después de un tiempo para clientes que podrían haber perdido el mensaje
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    // Solo enviar si el juego aún está activo y en la misma ronda
                    if (activeGames.containsKey(lobbyName) &&
                            "BIDDING".equals(activeGames.get(lobbyName).getStatus()) &&
                            activeGames.get(lobbyName).getCurrentRound() == roundData.getRound()) {
                        server.getRoomOperations(lobbyName).sendEvent("newRound", roundData);
                        logger.info("Enviando evento newRound de respaldo para lobby {}, ronda {}/{}",
                                lobbyName, roundData.getRound(), roundData.getTotalRounds());
                    }
                }
            }, 2000); // 2 segundos después

            // Configurar un temporizador para finalizar la subasta después de un tiempo determinado
            setupAuctionTimer(lobbyName, 30); // 30 segundos por ronda

            logger.info("Nueva ronda iniciada en lobby {}: Ronda {}/{}",
                    lobbyName, gameState.getCurrentRound(), gameState.getTotalRounds());
        } catch (Exception e) {
            logger.error("Error al iniciar nueva ronda en lobby {}: {}", lobbyName, e.getMessage(), e);
            endAuctionRound(lobbyName);
        }
    }

    // Método para configurar un temporizador para la subasta actual
    private void setupAuctionTimer(String lobbyName, int seconds) {
        // Cancelar cualquier temporizador existente
        if (gameTimers.containsKey(lobbyName)) {
            gameTimers.get(lobbyName).cancel();
        }

        // Crear un nuevo temporizador
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                endAuctionRound(lobbyName);
            }
        }, seconds * 1000L);

        gameTimers.put(lobbyName, timer);

        // Notificar a los clientes sobre el tiempo restante
        server.getRoomOperations(lobbyName).sendEvent("auctionTimer", seconds);
    }

    // Método para manejar las apuestas de los jugadores
    private DataListener<PlaceBidData> onPlaceBid() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            String nickname = data.getNickname();
            int amount = data.getAmount();

            logger.info("Apuesta recibida de {} en lobby {}: ${}", nickname, lobbyName, amount);

            // Verificar si el juego existe
            if (!activeGames.containsKey(lobbyName)) {
                sendErrorToClient(client, "Juego no encontrado", ackRequest);
                return;
            }

            GameState gameState = activeGames.get(lobbyName);

            // Verificar que el juego esté en estado de apuestas
            if (!"BIDDING".equals(gameState.getStatus())) {
                sendErrorToClient(client, "No se pueden realizar apuestas en este momento", ackRequest);
                return;
            }

            // Verificar que el jugador exista
            PlayerState player = findPlayerByNickname(lobbyName, nickname);
            if (player == null) {
                sendErrorToClient(client, "Jugador no encontrado en el juego", ackRequest);
                return;
            }

            // Verificar saldo y monto de apuesta
            if (player.getBalance() < amount) {
                sendErrorToClient(client, "Saldo insuficiente para realizar esta apuesta", ackRequest);
                return;
            }

            if (amount <= gameState.getCurrentBid()) {
                sendErrorToClient(client, "La apuesta debe ser mayor que la apuesta actual", ackRequest);
                return;
            }

            try {
                // Obtener el apostador anterior para devolverle su dinero
                String previousBidder = gameState.getLastBidder();
                int previousBid = gameState.getCurrentBid();

                // Si hay un apostador anterior, devolverle su dinero
                if (previousBidder != null && !previousBidder.equals(nickname)) {
                    PlayerState previousPlayer = findPlayerByNickname(lobbyName, previousBidder);
                    if (previousPlayer != null) {
                        // Devolver la apuesta anterior al saldo del jugador
                        previousPlayer.setBalance(previousPlayer.getBalance() + previousBid);

                        // Notificar la actualización del saldo
                        PlayerUpdateData updateData = new PlayerUpdateData();
                        updateData.setNickname(previousBidder);
                        updateData.setBalance(previousPlayer.getBalance());
                        updateData.setScore(previousPlayer.getScore());

                        server.getRoomOperations(lobbyName).sendEvent("playerUpdate", updateData);
                        logger.info("Devolviendo ${} al jugador anterior {}", previousBid, previousBidder);
                    }
                }

                // Enviar la apuesta al servicio de BidService
                ContainerInfo container = gameState.getCurrentContainer();

                // Crear una URL simplificada
                String apiUrl = BID_SERVICE_URL + "/bids/offer";

                // Usar un Map<String, String> para los parámetros
                Map<String, String> params = new HashMap<>();
                params.put("container", container.getId());
                params.put("owner", nickname);
                params.put("amount", String.valueOf(amount));

                // Configurar los headers
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Ocp-Apim-Subscription-Key", API_KEY);

                // Crear la entidad HTTP con el cuerpo JSON
                HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(params, headers);

                // Hacer la petición POST con el cuerpo JSON
                logger.info("Enviando apuesta a {} con contenedor: {}, dueño: {}, monto: {}",
                        apiUrl, container.getId(), nickname, amount);

                ResponseEntity<Object> response = restTemplate.postForEntity(
                        apiUrl,
                        requestEntity,
                        Object.class
                );

                logger.info("Respuesta del servicio de apuestas: {}", response.getStatusCode());

                // Actualizar el estado del juego
                gameState.setCurrentBid(amount);
                gameState.setLastBidder(nickname);

                // Restar el monto de la apuesta del saldo del jugador
                player.setBalance(player.getBalance() - amount);

                // Notificar la actualización del saldo del nuevo apostador
                PlayerUpdateData newBidderUpdate = new PlayerUpdateData();
                newBidderUpdate.setNickname(nickname);
                newBidderUpdate.setBalance(player.getBalance());
                newBidderUpdate.setScore(player.getScore());

                server.getRoomOperations(lobbyName).sendEvent("playerUpdate", newBidderUpdate);

                // Enviar la nueva apuesta a todos los jugadores
                server.getRoomOperations(lobbyName).sendEvent("newBid",
                        new NewBidData(nickname, amount));

                // Reiniciar el temporizador para dar más tiempo
                setupAuctionTimer(lobbyName, 15); // 15 segundos adicionales después de una apuesta

                if (ackRequest.isAckRequested()) {
                    ackRequest.sendAckData("Apuesta realizada con éxito");
                }
            } catch (Exception e) {
                logger.error("Error al procesar apuesta de {} en lobby {}: {}",
                        nickname, lobbyName, e.getMessage(), e);
                sendErrorToClient(client, "Error al procesar la apuesta: " + e.getMessage(), ackRequest);
            }
        };
    }

    // Método para finalizar una ronda de subasta (modificado)
    private void endAuctionRound(String lobbyName) {
        if (!activeGames.containsKey(lobbyName)) {
            logger.warn("No se puede finalizar la ronda. Juego no encontrado: {}", lobbyName);
            return;
        }

        GameState gameState = activeGames.get(lobbyName);

        // Actualizar estado
        gameState.setStatus("REVEALING");

        // Cancelar cualquier temporizador activo
        if (gameTimers.containsKey(lobbyName)) {
            gameTimers.get(lobbyName).cancel();
            gameTimers.remove(lobbyName);
        }

        // Determinar el ganador de la ronda
        String winner = gameState.getLastBidder();
        ContainerInfo container = gameState.getCurrentContainer();
        int bidAmount = gameState.getCurrentBid();

        // Si nadie hizo una apuesta, pasar a la siguiente ronda
        if (winner == null) {
            logger.info("Nadie hizo una apuesta en lobby {}. Pasando a la siguiente ronda.", lobbyName);
            gameState.setCurrentRound(gameState.getCurrentRound() + 1);
            startNewRound(lobbyName);
            return;
        }

        try {
            // Cerrar la apuesta actual
            String closeUrl = BID_SERVICE_URL + "/bids/close/" + container.getId();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Ocp-Apim-Subscription-Key", API_KEY);

            HttpEntity<Object> requestEntity = new HttpEntity<>(headers);

            // Hacer la petición POST para cerrar la apuesta
            logger.info("Cerrando apuesta para el contenedor: {}", container.getId());
            ResponseEntity<Object> closeResponse = restTemplate.exchange(
                    closeUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Object.class
            );

            logger.info("Respuesta al cerrar apuesta: {}", closeResponse.getStatusCode());
        } catch (Exception e) {
            logger.error("Error al cerrar apuesta para contenedor {}: {}",
                    container.getId(), e.getMessage());
            // Continuamos con el proceso a pesar del error
        }

        // Calcular resultados
        int containerValue = container.getValue();
        int profit = containerValue - bidAmount;

        // Actualizar el saldo y puntuación del ganador
        PlayerState winnerPlayer = findPlayerByNickname(lobbyName, winner);
        if (winnerPlayer != null) {
            // CAMBIO: Primero enviamos el beneficio al servicio externo
            // Esto actualizará el balance en el servicio y nos devolverá el nuevo valor
            updateUserBalance(winner, profit);

            // Importante: Mantenemos la lógica original, pero el balance real será actualizado
            // desde el servicio a través del método updateUserBalance y updatePlayerBalanceFromService
            winnerPlayer.setBalance(winnerPlayer.getBalance() + containerValue);
            winnerPlayer.setScore(winnerPlayer.getScore() + profit);

            // Enviar actualización del jugador ganador
            PlayerUpdateData winnerUpdate = new PlayerUpdateData();
            winnerUpdate.setNickname(winner);
            winnerUpdate.setBalance(winnerPlayer.getBalance());
            winnerUpdate.setScore(winnerPlayer.getScore());

            server.getRoomOperations(lobbyName).sendEvent("playerUpdate", winnerUpdate);
        }

        // Enviar resultado a todos los jugadores
        BidResultData resultData = new BidResultData();
        resultData.setWinner(winner);
        resultData.setContainerId(container.getId());
        resultData.setContainerType(container.getType());
        resultData.setBidAmount(bidAmount);
        resultData.setContainerValue(containerValue);
        resultData.setProfit(profit);

        server.getRoomOperations(lobbyName).sendEvent("bidResult", resultData);

        // Revelar el contenedor
        server.getRoomOperations(lobbyName).sendEvent("containerRevealed", container);

        // Enviar actualizaciones del estado de los jugadores
        List<PlayerState> players = gamePlayers.get(lobbyName);
        for (PlayerState player : players) {
            PlayerUpdateData playerData = new PlayerUpdateData();
            playerData.setNickname(player.getNickname());
            playerData.setBalance(player.getBalance());
            playerData.setScore(player.getScore());

            server.getRoomOperations(lobbyName).sendEvent("playerUpdate", playerData);
        }

        logger.info("Subasta finalizada en lobby {}. Ganador: {}, Beneficio: ${}",
                lobbyName, winner, profit);

        gameState.setCurrentRound(gameState.getCurrentRound() + 1);
    }

    // Método para manejar cuando un jugador abandona el juego
    private DataListener<LeaveGameData> onLeaveGame() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            String nickname = data.getNickname();

            logger.info("Jugador {} abandonando el juego en lobby {}", nickname, lobbyName);

            // Manejar la salida del jugador
            handlePlayerLeaveGame(lobbyName, nickname);

            if (ackRequest.isAckRequested()) {
                ackRequest.sendAckData("Has abandonado el juego");
            }
        };
    }

    // Método para manejar la salida de un jugador del juego
    private void handlePlayerLeaveGame(String lobbyName, String nickname) {
        if (!activeGames.containsKey(lobbyName)) {
            return;
        }

        // Notificar a los demás jugadores
        server.getRoomOperations(lobbyName).sendEvent("playerLeftGame", new PlayerLeftGameData(nickname));

        // Eliminar al jugador de la lista
        List<PlayerState> players = gamePlayers.get(lobbyName);
        if (players != null) {
            players.removeIf(p -> p.getNickname().equals(nickname));

            // Si quedan menos de 2 jugadores, finalizar el juego
            if (players.size() < 2) {
                logger.info("Quedan menos de 2 jugadores en lobby {}. Finalizando juego.", lobbyName);
                endGame(lobbyName);
            }
        }
    }

    // Método para finalizar el juego
    // Método para finalizar el juego
    private void endGame(String lobbyName) {
        if (!activeGames.containsKey(lobbyName)) {
            logger.warn("No se puede finalizar el juego. Juego no encontrado: {}", lobbyName);
            return;
        }

        logger.info("Finalizando juego en lobby {}", lobbyName);

        // Actualizar el estado del juego a FINISHED para evitar cualquier procesamiento adicional
        GameState gameState = activeGames.get(lobbyName);
        gameState.setStatus("FINISHED");

        // Cancelar cualquier temporizador activo
        if (gameTimers.containsKey(lobbyName)) {
            gameTimers.get(lobbyName).cancel();
            gameTimers.remove(lobbyName);
        }

        // Determinar ganador
        List<PlayerState> players = gamePlayers.get(lobbyName);
        if (players != null && !players.isEmpty()) {
            // Encontrar el jugador con mayor puntuación
            PlayerState winner = players.get(0);
            for (PlayerState player : players) {
                if (player.getScore() > winner.getScore()) {
                    winner = player;
                }
            }

            // Crear datos del resultado final
            GameEndData endData = new GameEndData();
            endData.setWinner(winner.getNickname());
            endData.setFinalScores(players);

            try {
                // Enviar resultado a todos los jugadores con múltiples intentos
                for (int attempt = 0; attempt < 3; attempt++) {
                    server.getRoomOperations(lobbyName).sendEvent("gameEnd", endData);
                    logger.info("Intento {} - Enviando evento gameEnd para lobby {}. Ganador: {}",
                            attempt + 1, lobbyName, winner.getNickname());

                    // Pequeña pausa entre intentos
                    if (attempt < 2) Thread.sleep(500);
                }

                // Esperar un momento y enviar un evento de respaldo para asegurar que todos reciban la notificación
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            // Verificar si el juego aún está en la colección (por si fue removido)
                            if (activeGames.containsKey(lobbyName)) {
                                server.getRoomOperations(lobbyName).sendEvent("gameEnd", endData);
                                logger.info("Enviando evento gameEnd de respaldo para lobby {}", lobbyName);
                            }
                        } catch (Exception e) {
                            logger.error("Error al enviar evento gameEnd de respaldo: {}", e.getMessage());
                        }
                    }
                }, 2000); // Enviar evento de respaldo después de 2 segundos
            }catch (InterruptedException ie) {
                // Re-interrumpir el hilo
                Thread.currentThread().interrupt();
                // Loguear la interrupción
                logger.warn("Hilo interrumpido mientras se enviaban eventos de finalización para lobby {}", lobbyName);
            }
            catch (Exception e) {
                logger.error("Error al enviar evento gameEnd: {}", e.getMessage());
            } finally {
                // Asegurarnos de limpiar recursos
                cleanupGame(lobbyName);
            }
        } else {
            logger.warn("No hay jugadores en el juego al finalizar. Lobby: {}", lobbyName);
            cleanupGame(lobbyName);
        }
    }

    // Método auxiliar para limpiar recursos del juego
    private void cleanupGame(String lobbyName) {
        try {
            // Limpiar recursos con un pequeño retraso para asegurar que todos los eventos se procesen
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    activeGames.remove(lobbyName);
                    gamePlayers.remove(lobbyName);
                    gameContainers.remove(lobbyName);
                    logger.info("Recursos del juego liberados para lobby {}", lobbyName);
                }
            }, 5000); // 5 segundos de espera antes de limpiar
        } catch (Exception e) {
            logger.error("Error al limpiar recursos del juego: {}", e.getMessage());
        }
    }


    private DataListener<ReadyForNextRoundData> onReadyForNextRound() {
        return (client, data, ackRequest) -> {
            String lobbyName = data.getLobbyName();
            String nickname = data.getNickname();

            logger.info("Jugador {} está listo para la siguiente ronda en lobby {}", nickname, lobbyName);

            if (!activeGames.containsKey(lobbyName)) {
                logger.warn("No se encontró juego activo para lobby: {}", lobbyName);
                return;
            }

            // Obtener o crear el conjunto de jugadores listos para este lobby
            Set<String> readyPlayers = playersReadyForNextRound.computeIfAbsent(lobbyName, k -> new HashSet<>());

            // Añadir este jugador al conjunto
            readyPlayers.add(nickname);

            // Notificar a todos los jugadores sobre el nuevo jugador listo
            server.getRoomOperations(lobbyName).sendEvent("playerReadyForNextRound",
                    new ReadyPlayerData(nickname, lobbyName));

            // Verificar si todos los jugadores están listos
            List<PlayerState> allPlayers = gamePlayers.get(lobbyName);
            if (allPlayers != null && !allPlayers.isEmpty()) {
                int totalPlayers = allPlayers.size();
                int readyCount = readyPlayers.size();

                logger.info("Estado de listos en lobby {}: {}/{}", lobbyName, readyCount, totalPlayers);

                if (readyCount >= totalPlayers) {
                    // Todos los jugadores están listos
                    logger.info("Todos los jugadores están listos para la siguiente ronda en lobby {}", lobbyName);

                    // Notificar a todos que todos están listos
                    server.getRoomOperations(lobbyName).sendEvent("allPlayersReadyForNextRound",
                            new AllReadyData(lobbyName));

                    // Limpiar el conjunto de jugadores listos
                    readyPlayers.clear();

                    // Si el juego está en el último round, finalizarlo
                    GameState gameState = activeGames.get(lobbyName);
                    if (gameState.getCurrentRound() > gameState.getTotalRounds()) {
                        endGame(lobbyName);
                    } else {
                        // Iniciar la siguiente ronda
                        startNewRound(lobbyName);
                    }
                }
            }
        };
    }

    public void notifyGameStarted(String lobbyName) {
        server.getRoomOperations(lobbyName).sendEvent("gameStarted", lobbyName);
        logger.info("Notificación de inicio de juego enviada al lobby {}", lobbyName);
    }

    public void notifyRoundEnded(String lobbyName, int remainingRounds) {
        server.getRoomOperations(lobbyName).sendEvent("roundEnded",
                new RoundEndedData(lobbyName, remainingRounds));
        logger.info("Notificación de fin de ronda enviada al lobby {}. Rondas restantes: {}",
                lobbyName, remainingRounds);
    }

    public void notifyGameEnded(String lobbyName) {
        // Evento opcional que podría utilizarse al integrar con otros componentes
        server.getRoomOperations(lobbyName).sendEvent("gameEnded", lobbyName);
        logger.info("Notificación de fin de juego enviada al lobby {}", lobbyName);
    }

    private Queue<ContainerInfo> generateContainers(int count) {
        Queue<ContainerInfo> containers = new LinkedList<>();
        SecureRandom random = new SecureRandom();

        for (int i = 0; i < count; i++) {
            try {
                // Configurar los headers para la petición
                HttpHeaders headers = new HttpHeaders();
                headers.set("Ocp-Apim-Subscription-Key", API_KEY);
                HttpEntity<Object> requestEntity = new HttpEntity<>(headers);

                // Realizar la petición GET al microservicio de contenedores
                String apiUrl = "https://thehiddencargo1.azure-api.net/api/contenedor";
                ResponseEntity<Map> response = restTemplate.exchange(
                        apiUrl,
                        HttpMethod.GET,
                        requestEntity,
                        Map.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> containerData = response.getBody();
                    if(containerData != null) {

                        ContainerInfo container = new ContainerInfo();
                        container.setId("container-" + UUID.randomUUID().toString().substring(0, 8));

                        // Obtener el color del contenedor y usarlo para determinar el tipo
                        String color = (String) containerData.get("color");

                        // Mapear color a tipo (adaptar según necesites)
                        String type;
                        if ("gris".equalsIgnoreCase(color)) {
                            type = "Raro";
                        } else if ("blanco".equalsIgnoreCase(color)) {
                            type = "Normal";
                        } else if ("azul".equalsIgnoreCase(color)) {
                            type = "Épico";
                        } else if ("dorado".equalsIgnoreCase(color)) {
                            type = "Legendario";
                        } else {
                            type = "Normal"; // Por defecto
                        }
                        container.setType(type);

                        // Obtener y procesar objetos para calcular el valor
                        List<Map<String, Object>> objetos = (List<Map<String, Object>>) containerData.get("objetos");
                        if (objetos != null && !objetos.isEmpty()) {
                            // Calcular el valor total como suma de precios de los objetos
                            double valorTotal = 0;
                            for (Map<String, Object> objeto : objetos) {
                                if (objeto.containsKey("precio")) {
                                    double precio = ((Number) objeto.get("precio")).doubleValue();
                                    valorTotal += precio;
                                }
                            }

                            // Establecer el valor total redondeado a entero
                            container.setValue((int) Math.round(valorTotal));

                            // Guardar información adicional en el ID para recuperarla después
                            // Formato: container-[UUID]-color:[color]-objects:[objeto1,precio1;objeto2,precio2]
                            StringBuilder idBuilder = new StringBuilder(container.getId());
                            idBuilder.append("-color:").append(color);
                            idBuilder.append("-objects:");

                            for (int j = 0; j < objetos.size(); j++) {
                                Map<String, Object> objeto = objetos.get(j);
                                String nombre = (String) objeto.get("nombre");
                                double precio = ((Number) objeto.get("precio")).doubleValue();

                                idBuilder.append(nombre).append(",").append(precio);
                                if (j < objetos.size() - 1) {
                                    idBuilder.append(";");
                                }
                            }

                            container.setId(idBuilder.toString());
                        } else {
                            // Si no hay objetos, asignar un valor por defecto según el tipo
                            int valor;
                            switch (type) {
                                case "Raro":
                                    valor = 500 + random.nextInt(500);
                                    break;
                                case "Épico":
                                    valor = 1000 + random.nextInt(1000);
                                    break;
                                case "Legendario":
                                    valor = 2000 + random.nextInt(3000);
                                    break;
                                default: // Normal
                                    valor = 200 + random.nextInt(300);
                            }
                            container.setValue(valor);
                        }

                        logger.info("Contenedor obtenido de API: id={}, tipo={}, valor={}",
                                container.getId(), container.getType(), container.getValue());

                        containers.add(container);
                    } else {
                        logger.warn("Error al obtener contenedor desde API. Generando uno local. Respuesta: {}",
                                response.getStatusCode());
                        // Generar contenedor local en caso de error

                    }
                    }

            } catch (Exception e) {
                logger.error("Error al comunicarse con API de contenedores: {}", e.getMessage());
                // Generar contenedor local en caso de error

            }
        }

        return containers;
    }

    // Método auxiliar para encontrar un jugador por su nickname
    private PlayerState findPlayerByNickname(String lobbyName, String nickname) {
        List<PlayerState> players = gamePlayers.get(lobbyName);
        if (players != null) {
            for (PlayerState player : players) {
                if (player.getNickname().equals(nickname)) {
                    return player;
                }
            }
        }
        return null;
    }

    // Método auxiliar para enviar errores al cliente
    private void sendErrorToClient(SocketIOClient client, String errorMessage, AckRequest ackRequest) {
        logger.warn(errorMessage);
        if (ackRequest.isAckRequested()) {
            ackRequest.sendAckData("Error: " + errorMessage);
        }
    }
}

// Clases de datos para eventos del juego
class StartGameData {
    private String lobbyName;

    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class GameStartedData {
    private List<String> players;
    private ContainerInfo container;
    private int initialBid;
    private int round;
    private int totalRounds;

    public List<String> getPlayers() { return players; }
    public void setPlayers(List<String> players) { this.players = players; }
    public ContainerInfo getContainer() { return container; }
    public void setContainer(ContainerInfo container) { this.container = container; }
    public int getInitialBid() { return initialBid; }
    public void setInitialBid(int initialBid) { this.initialBid = initialBid; }
    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
}

class PlaceBidData {
    private String nickname;
    private String lobbyName;
    private String containerId;
    private int amount;

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
}

class NewBidData {
    private String nickname;
    private int amount;

    public NewBidData() {}

    public NewBidData(String nickname, int amount) {
        this.nickname = nickname;
        this.amount = amount;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
}

class BidResultData {
    private String winner;
    private String containerId;
    private String containerType;
    private int bidAmount;
    private int containerValue;
    private int profit;

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public String getContainerType() { return containerType; }
    public void setContainerType(String containerType) { this.containerType = containerType; }
    public int getBidAmount() { return bidAmount; }
    public void setBidAmount(int bidAmount) { this.bidAmount = bidAmount; }
    public int getContainerValue() { return containerValue; }
    public void setContainerValue(int containerValue) { this.containerValue = containerValue; }
    public int getProfit() { return profit; }
    public void setProfit(int profit) { this.profit = profit; }
}

class NewRoundData {
    private int round;
    private int totalRounds;
    private ContainerInfo container;
    private int initialBid;

    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
    public ContainerInfo getContainer() { return container; }
    public void setContainer(ContainerInfo container) { this.container = container; }
    public int getInitialBid() { return initialBid; }
    public void setInitialBid(int initialBid) { this.initialBid = initialBid; }
}

class PlayerUpdateData {
    private String nickname;
    private int balance;
    private int score;

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public int getBalance() { return balance; }
    public void setBalance(int balance) { this.balance = balance; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
}

class LeaveGameData {
    private String nickname;
    private String lobbyName;

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class PlayerLeftGameData {
    private String nickname;

    public PlayerLeftGameData() {}

    public PlayerLeftGameData(String nickname) {
        this.nickname = nickname;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
}

class GameEndData {
    private String winner;
    private List<PlayerState> finalScores;

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
    public List<PlayerState> getFinalScores() { return finalScores; }
    public void setFinalScores(List<PlayerState> finalScores) { this.finalScores = finalScores; }
}

class GameState {
    private String lobbyName;
    private int currentRound;
    private int totalRounds;
    private String status; // STARTING, BIDDING, REVEALING, FINISHED, ERROR
    private ContainerInfo currentContainer;
    private int currentBid;
    private String lastBidder;

    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public int getTotalRounds() { return totalRounds; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public ContainerInfo getCurrentContainer() { return currentContainer; }
    public void setCurrentContainer(ContainerInfo currentContainer) { this.currentContainer = currentContainer; }
    public int getCurrentBid() { return currentBid; }
    public void setCurrentBid(int currentBid) { this.currentBid = currentBid; }
    public String getLastBidder() { return lastBidder; }
    public void setLastBidder(String lastBidder) { this.lastBidder = lastBidder; }
}

class PlayerState {
    private String nickname;
    private int balance;
    private int score;

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public int getBalance() { return balance; }
    public void setBalance(int balance) { this.balance = balance; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
}

class ContainerInfo {
    private String id;
    private String type;
    private int value;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }
}

// Clases de datos para los eventos
class JoinLobbyData {
    private String nickname;
    private String lobbyName;

    public JoinLobbyData() {}

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class LeaveLobbyData {
    private String lobbyName;

    public LeaveLobbyData() {}

    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class PlayerJoinedData {
    private String nickname;

    public PlayerJoinedData() {}

    public PlayerJoinedData(String nickname) {
        this.nickname = nickname;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
}

class PlayerLeftData {
    private String nickname;

    public PlayerLeftData() {}

    public PlayerLeftData(String nickname) {
        this.nickname = nickname;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
}

class PlayerReadyData {
    private String nickname;
    private String lobbyName;

    public PlayerReadyData() {}

    public PlayerReadyData(String nickname, String lobbyName) {
        this.nickname = nickname;
        this.lobbyName = lobbyName;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class PlayerNotReadyData {
    private String nickname;
    private String lobbyName;

    public PlayerNotReadyData() {}

    public PlayerNotReadyData(String nickname, String lobbyName) {
        this.nickname = nickname;
        this.lobbyName = lobbyName;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}
class ReadyPlayerData {
    private String nickname;
    private String lobbyName;

    public ReadyPlayerData() {}

    public ReadyPlayerData(String nickname, String lobbyName) {
        this.nickname = nickname;
        this.lobbyName = lobbyName;
    }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}
class PlayerBalanceData {
    private String nickname;
    private String lobbyName;
    private int initialBalance;

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
    public int getInitialBalance() { return initialBalance; }
    public void setInitialBalance(int initialBalance) { this.initialBalance = initialBalance; }
}

class AllReadyData {
    private String lobbyName;

    public AllReadyData() {}

    public AllReadyData(String lobbyName) {
        this.lobbyName = lobbyName;
    }

    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class ChatMessageData {
    private String nickname;
    private String lobbyName;
    private String message;

    public ChatMessageData() {}

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
class ReadyForNextRoundData {
    private String nickname;
    private String lobbyName;

    public ReadyForNextRoundData() {}

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
}

class RoundEndedData {
    private String lobbyName;
    private int remainingRounds;

    public RoundEndedData() {}

    public RoundEndedData(String lobbyName, int remainingRounds) {
        this.lobbyName = lobbyName;
        this.remainingRounds = remainingRounds;
    }

    public String getLobbyName() { return lobbyName; }
    public void setLobbyName(String lobbyName) { this.lobbyName = lobbyName; }
    public int getRemainingRounds() { return remainingRounds; }
    public void setRemainingRounds(int remainingRounds) { this.remainingRounds = remainingRounds; }
}