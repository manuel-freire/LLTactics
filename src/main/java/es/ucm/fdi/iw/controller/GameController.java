package es.ucm.fdi.iw.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import es.ucm.fdi.iw.model.GameBattleResult;
import es.ucm.fdi.iw.model.GameItem;
import es.ucm.fdi.iw.model.GameMessage;
import es.ucm.fdi.iw.model.GamePlayer;
import es.ucm.fdi.iw.model.GameRoom;
import es.ucm.fdi.iw.model.GameRoom.Phase;
import es.ucm.fdi.iw.model.GameUnit;
import es.ucm.fdi.iw.model.Heroe;
import es.ucm.fdi.iw.model.Jugador;
import es.ucm.fdi.iw.model.Message;
import es.ucm.fdi.iw.model.Objeto;
import es.ucm.fdi.iw.model.PlayerAction;
import es.ucm.fdi.iw.model.Topic;
import es.ucm.fdi.iw.model.Unidad;
import es.ucm.fdi.iw.model.User.Role;
import jakarta.servlet.http.HttpSession;
import es.ucm.fdi.iw.model.User;
import es.ucm.fdi.iw.model.PlayerAction.ActionType;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Controller
public class GameController {

    @Autowired
    private EntityManager entityManager;

    private static final Logger log = LogManager.getLogger(GameController.class);

    // Almacenamos las salas de juego activas
    private static final Map<String, GameRoom> activeGames = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    @ModelAttribute
    public void populateModel(HttpSession session, Model model) {        
        for (String name : new String[] {"u", "url", "ws", "gameId"}) {
            model.addAttribute(name, session.getAttribute(name));
        }
    }

    // Método para añadir una sala de juego activa (por ejemplo, desde el matchmaking)
    public void addActiveGame(String gameRoomId, String player1, String player2) {

        activeGames.put(gameRoomId, new GameRoom(gameRoomId, player1, player2));

        // Espera 5 segundos antes de empezar la partida
        scheduler.schedule(() -> {
            startBuyPhase(gameRoomId);
        }, 5, TimeUnit.SECONDS);
    }
    
    @GetMapping("/game/{gameRoomId}")
    @Transactional
    public String showGamePage(@PathVariable String gameRoomId, HttpSession session, Model model, HttpServletResponse response, Principal principal) {
        // Verificar si la sala de juego existe
        GameRoom gameRoom = activeGames.get(gameRoomId);

        if (gameRoom == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "errorPage";  // Devuelve una página de error si la sala no existe
        }

        String currentUsername = principal.getName();
        // Cargar los datos de la sala de juego
        model.addAttribute("gameRoomId", gameRoom.getGameRoomId());
        session.setAttribute("gameId", gameRoom.getGameRoomId());

        Map<String, GamePlayer> players = gameRoom.getPlayers();
        GamePlayer player1 = players.get(currentUsername);

        GamePlayer player2 = players.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(currentUsername))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        
        model.addAttribute("player1", player1);
        model.addAttribute("player2", player2);

        List<GameItem> playerObjects = new ArrayList<>();
        for (GameItem item : player1.getInventory()) {
            playerObjects.add(item);
        }
        while (playerObjects.size() < GamePlayer.MAX_ITEMS) {
            playerObjects.add(new GameItem());
        }   
        model.addAttribute("playerObjects", playerObjects);

        List<GameItem> opponentObjects = new ArrayList<>();
        for (GameItem item : player2.getInventory()) {
            opponentObjects.add(item);
        }
        while (opponentObjects.size() < GamePlayer.MAX_ITEMS) {
            opponentObjects.add(new GameItem());
        }   
        model.addAttribute("opponentObjects", opponentObjects);


        List<Objeto> shopItems = List.of(
                new Objeto("/img/items/potion-red.png", "Poción Roja", 0, 0, 0, 0,"", 1),
                new Objeto("/img/items/flame-sword.png", "Espada de Hierro", 0, 0, 0, 0,"", 5));
        model.addAttribute("shopItems", shopItems);

        List<Heroe> shopUnits = List.of(
                new Heroe("Dragón", "/img/units/dragons/4. DGris/burner.png", 0, 0, 0, 0, null, 0, 2, 0),
                new Heroe("Esqueleto", "/img/units/humans/5. Mago/white-mage.png", 0, 0, 0, 0, null, 0, 4, 0),
                new Heroe("Mago", "/img/units/humans/5. Mago/white-mage.png", 0, 0, 0, 0, null, 0, 1, 0));
        model.addAttribute("shopUnits", shopUnits);


        List<GameUnit> unitsP1 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            unitsP1.add(GameUnit.getNullUnit());
        }
        model.addAttribute("unitsP1", unitsP1);

        List<GameUnit> unitsP2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            unitsP2.add(GameUnit.getNullUnit());
        }
        model.addAttribute("unitsP2", unitsP2);

        List<GameMessage> mensajes = gameRoom.getMessageHistory();
        model.addAttribute("mensajes", mensajes);

        return "game";
    }

    // Método para recibir la acción de un jugador
    @PostMapping("/game/action/{gameRoomId}")
    @Transactional
    @ResponseBody
    public void handlePlayerAction(@PathVariable String gameRoomId, @RequestBody PlayerAction action, Principal principal) {
        // Verificar si la sala de juego existe
        GameRoom gameRoom = activeGames.get(gameRoomId);

        if (gameRoom == null) {
            log.error("Intento de acción en una sala no existente: {}", gameRoomId);
            return;
        }

        String playerName = principal.getName();
        
        /*
        // Verificar si el jugador está en la partida
        if (!gameRoom.getPlayers().contains(playerName)) {
            log.error("El jugador {} no pertenece a la partida {}", playerName, gameRoomId);
            return;
        }
        */
        try {
            processAction(gameRoom, action, playerName);
        } catch(JsonProcessingException e) {
            log.warn("Error al procesar la acción del jugador {}: {}", playerName, action, e);
        }

        // Difundir la acción a los demás jugadores
        sendActionToPlayers(gameRoom, action, playerName);
    }

    private void processAction(GameRoom gameRoom, PlayerAction action, String senderPlayer) throws JsonProcessingException {
        ActionType type = action.getActionType();
        String details = action.getActionDetails();

        ObjectMapper mapper = new ObjectMapper();

        switch (type) {
            case BUY_UNIT:
                GameUnit unitBought = mapper.readValue(details, GameUnit.class);
                gameRoom.playerBuyUnit(senderPlayer, unitBought);
                break;
            case SELL_UNIT:
                GameUnit unitSold = mapper.readValue(details, GameUnit.class);
                gameRoom.playerSellUnit(senderPlayer, unitSold);
                break;
            case BUY_ITEM:
                GameItem itemBought = mapper.readValue(details, GameItem.class);
                gameRoom.playerBuyItem(senderPlayer, itemBought);
                break;
            case SELL_ITEM:
                GameItem itemSold = mapper.readValue(details, GameItem.class);
                gameRoom.playerSellItem(senderPlayer, itemSold);
                break;
            case ASSIGN_ITEM_TO_UNIT:
                GameItem itemAssigned = mapper.readValue(details, GameItem.class);
                gameRoom.playerAssignItemToUnit(senderPlayer, itemAssigned.getUnitUnitId(), itemAssigned);
                break;
            case REFRESH_SHOP:
                // Lógica para refrescar la tienda
                break;
            case SEND_MESSAGE:
                gameRoom.addMessage(senderPlayer, details, ZonedDateTime.now());
                break;
            default:
                log.warn("Acción desconocida: {}", action);
                break;
        }        

    }

    private void sendActionToPlayers(GameRoom gameRoom, PlayerAction action) {
        sendActionToPlayers(gameRoom, action, "server");
    }

    private void sendActionToPlayers(GameRoom gameRoom, PlayerAction action, String senderPlayer) {

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> payload = new HashMap<>();
        GamePlayer senderDataUnits = gameRoom.getPlayers().get(senderPlayer);
        GamePlayer senderDataItems = gameRoom.getPlayers().get(senderPlayer);
        boolean sendAll = true;
    
        switch (action.getActionType()) {
            case BUY_UNIT:
                payload.put("playerUnits", senderDataUnits.getUnits());
                payload.put("updateUnits", true);
                payload.put("updateItems", false);
                break;
            case BUY_ITEM:
            case SELL_ITEM:
                payload.put("updateUnits", false);
                payload.put("updateItems", true);
                payload.put("playerItems", new ArrayList<>(senderDataItems.getInventory()));
                break;
            case SELL_UNIT:
            case ASSIGN_ITEM_TO_UNIT:
                payload.put("playerUnits", senderDataUnits.getUnits());
                payload.put("playerItems", new ArrayList<>(senderDataItems.getInventory()));
                payload.put("updateUnits", true);
                payload.put("updateItems", true);
                break;
            case GENERAL:
                Map<String, GamePlayer> players = gameRoom.getPlayers();

                GamePlayer player1 = gameRoom.getPlayers().get(senderPlayer);
        
                GamePlayer player2 = players.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(senderPlayer))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
                payload.put("player1Units", player1.getUnits());
                payload.put("player1Items", new ArrayList<>(player1.getInventory()));
                payload.put("player1Health", player1.getHealth());
                payload.put("player1Stars", player1.getStars()) ;
                payload.put("player2Units", player2.getUnits());
                payload.put("player2Items", new ArrayList<>(player2.getInventory()));
                payload.put("player2Health", player2.getHealth());
                payload.put("player2Stars", player2.getStars());
                payload.put("player1Name", player1.getName());
                payload.put("player2Name", player2.getName());
                payload.put("updateAll", true);
                sendAll = false;
                break;
            case WINNER:
                String winnerDetails = action.getActionDetails();
                payload.put("isWinner", true);
                payload.put("winner", winnerDetails);
                break;
            case REFRESH_SHOP:
                sendAll = false;
                break;
            case SEND_MESSAGE:
                payload.put("newMessage", true);

                String message = action.getActionDetails();
                
                // Crear un mapa para el mensaje
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("text", message);
                messageMap.put("timestamp", ZonedDateTime.now());
                messageMap.put("playerName", senderPlayer);

                // Añadir el mensaje al payload
                payload.put("message", messageMap);

                break;
        }
    
        try {
            String jsonPayload = mapper.writeValueAsString(payload);
            messagingTemplate.convertAndSend(
                "/topic/game/" + gameRoom.getGameRoomId(),
                jsonPayload
            );
        } catch (JsonProcessingException e) {
            log.error("Error al convertir la acción a JSON: {}", action, e);
        }
    }

    @MessageMapping("/game/ready/{gameRoomId}")
    public void handleEndBattle(@DestinationVariable String gameRoomId, @Payload GameBattleResult playerResult, Principal principal) {
        GameRoom gameRoom = activeGames.get(gameRoomId);
        String playerName = principal.getName();
    
        synchronized (gameRoom) {

            
            gameRoom.setPlayerResult(playerName, playerResult);
            gameRoom.setPlayerReady(playerName);
            
            if (gameRoom.bothPlayersReady()) {
                GameBattleResult result1 = gameRoom.getPlayerResult(gameRoom.getPlayer1Name());
                GameBattleResult result2 = gameRoom.getPlayerResult(gameRoom.getPlayer2Name());

                if (result1 == null || result2 == null) return;
                
                gameRoom.resetReadiness();

                if (!gameRoom.resultsMatch(result1, result2)) {
                    // Manejar discrepancia
                    log.warn("Discrepancia en resultados de batalla entre jugadores.");
                    return;
                }

                System.out.println("TODO PIOLA");
                gameRoom.reduceLoserHealth();

                String winner = gameRoom.getWinner();
                if (winner != null) {


                    PlayerAction winnerAction = new PlayerAction(
                        PlayerAction.ActionType.WINNER, 
                        "server", 
                        winner
                    );

                    sendActionToPlayers(gameRoom, winnerAction);
                    return;
                }
    
                // Validar si ya se está en fase de transición
                if (!gameRoom.isInTransition()) {
                    gameRoom.setInTransition(true); // bandera para evitar duplicación
    
                    scheduler.schedule(() -> {
                        startBuyPhase(gameRoomId);
                        gameRoom.setInTransition(false); // liberar transición
                    }, 5, TimeUnit.SECONDS);
                }
            }
        }
    }
    

    private void startBuyPhase(String gameRoomId) {

        GameRoom gameRoom = activeGames.get(gameRoomId);
        if (gameRoom == null) return;

        log.info("Comienza fase de compra para sala {}", gameRoomId);
        gameRoom.setCurrentPhase(Phase.BUY);

        // Notificar a los jugadores que comienza la fase de compra
        Map<String, Object> payload = Map.of(
            "phase", "buy",
            "round", gameRoom.getCurrentRound(),
            "time", GameRoom.SHOP_TIME
        );
        for (String player : gameRoom.getPlayers().keySet()) {
            messagingTemplate.convertAndSendToUser(
                player,
                "/queue/game/" + gameRoomId + "/actions",
                payload
            );
        }

        // Iniciar temporizador de 30 segundos
        scheduler.schedule(() -> {
            log.info("Fase de compra terminó automáticamente para sala {}", gameRoomId);
            startBattlePhase(gameRoomId);
        }, GameRoom.SHOP_TIME + 1, TimeUnit.SECONDS);
    }

    private void startBattlePhase(String gameRoomId) {
        GameRoom gameRoom = activeGames.get(gameRoomId);
        if (gameRoom == null) return;
    
        log.info("Comienza fase de batalla para sala {}", gameRoomId);
    
        // Avanzamos la ronda
        gameRoom.nextRound();
    
        // Notificar a los jugadores que deben esperar y luego confirmar
        Map<String, Object> payload = Map.of(
            "phase", "battle",
            "round", gameRoom.getCurrentRound()
        );
        for (String player : gameRoom.getPlayers().keySet()) {
            messagingTemplate.convertAndSendToUser(
                player,
                "/queue/game/" + gameRoomId + "/actions",
                payload
            );
        }
    }

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /*
    private void resolveBattle(GameRoom gameRoom) {
        String loser = gameRoom.calculateBattleLoser(); // Método que debes definir según tu lógica
        if (loser != null) {
            GamePlayer losingPlayer = gameRoom.getPlayers().get(loser);
            losingPlayer.reduceHealth(5);
    
            if (losingPlayer.getHealth() <= 0) {
                endGame(gameRoom, loser);
            }
        }
    }

    private void endGame(GameRoom gameRoom, String loser) {
        String winner = gameRoom.getPlayers().keySet().stream()
            .filter(name -> !name.equals(loser))
            .findFirst()
            .orElse("Desconocido");
    
        Map<String, Object> payload = Map.of(
            "gameOver", true,
            "winner", winner
        );
    
        for (String player : gameRoom.getPlayers().keySet()) {
            messagingTemplate.convertAndSendToUser(
                player,
                "/queue/game/" + gameRoom.getGameRoomId() + "/actions",
                payload
            );
        }
    
        activeGames.remove(gameRoom.getGameRoomId());
    }
    */

}