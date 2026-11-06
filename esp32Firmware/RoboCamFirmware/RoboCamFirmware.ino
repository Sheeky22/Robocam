#include <Arduino.h>
#include <ArduinoJson.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include <WiFi.h>
#include <LittleFS.h>

namespace {

constexpr char AP_SSID[] = "RoboCam";
constexpr char AP_PASSWORD[] = "robocam123";
constexpr uint8_t WS_PORT = 81;
const IPAddress AP_IP(192, 168, 4, 1);
const IPAddress AP_GATEWAY(192, 168, 4, 1);
const IPAddress AP_SUBNET(255, 255, 255, 0);

// L298N wiring validated by the standalone motor test sketch.
constexpr uint8_t MOTOR_A_PWM_PIN = 21;
constexpr uint8_t MOTOR_B_PWM_PIN = 19;
constexpr uint8_t MOTOR_A_IN1_PIN = 18;
constexpr uint8_t MOTOR_A_IN2_PIN = 17;
constexpr uint8_t MOTOR_B_IN1_PIN = 16;
constexpr uint8_t MOTOR_B_IN2_PIN = 4;
constexpr uint32_t MOTOR_PWM_FREQUENCY = 30000;
constexpr uint8_t MOTOR_PWM_RESOLUTION = 8;
constexpr uint8_t MOTOR_PWM_CHANNEL_A = 0;
constexpr uint8_t MOTOR_PWM_CHANNEL_B = 1;
constexpr unsigned long DRIVE_WATCHDOG_MS = 750;
constexpr int AUTONOMOUS_MAX_PERCENT = 100;

WebServer httpServer(80);
WebSocketsServer webSocket(WS_PORT);

struct ClientSlot {
  uint8_t clientId = 0;
  bool connected = false;
  char role[6] = {};
};

ClientSlot webClient;
ClientSlot phoneClient;

bool isValidRole(const char* role) {
  return role != nullptr && (strcmp(role, "web") == 0 || strcmp(role, "phone") == 0);
}

enum class ControlMode { STOPPED, MANUAL, AUTO };
ControlMode controlMode = ControlMode::STOPPED;
int currentLeft = 0;
int currentRight = 0;
unsigned long lastDriveAt = 0;
bool emergencyStopped = false;
uint32_t lastPhoneSequence = 0;
bool hasPhoneSequence = false;
uint8_t activeDriveClient = 0;

int clampDrive(int value) {
  return constrain(value, -100, 100);
}

const char* controlModeName() {
  if (controlMode == ControlMode::MANUAL) return "MANUAL";
  if (controlMode == ControlMode::AUTO) return "AUTO";
  return "STOPPED";
}

void setMotor(uint8_t pwmPin, uint8_t in1Pin, uint8_t in2Pin, int percent) {
  int clamped = clampDrive(percent);
  if (clamped == 0) {
    digitalWrite(in1Pin, LOW);
    digitalWrite(in2Pin, LOW);
    ledcWrite(pwmPin, 0);
    return;
  }
  digitalWrite(in1Pin, clamped > 0 ? HIGH : LOW);
  digitalWrite(in2Pin, clamped > 0 ? LOW : HIGH);
  ledcWrite(pwmPin, map(abs(clamped), 0, 100, 0, 255));
}

void applyDrive(int left, int right) {
  if ((controlMode != ControlMode::MANUAL && controlMode != ControlMode::AUTO) || emergencyStopped) {
    left = 0;
    right = 0;
  }
  if (controlMode == ControlMode::AUTO) {
    left = constrain(left, -AUTONOMOUS_MAX_PERCENT, AUTONOMOUS_MAX_PERCENT);
    right = constrain(right, -AUTONOMOUS_MAX_PERCENT, AUTONOMOUS_MAX_PERCENT);
  }
  currentLeft = clampDrive(left);
  currentRight = clampDrive(right);
  Serial.printf("Drive output mode=%s left=%d right=%d\n",
                controlModeName(), currentLeft, currentRight);
  setMotor(MOTOR_A_PWM_PIN, MOTOR_A_IN1_PIN, MOTOR_A_IN2_PIN, currentLeft);
  setMotor(MOTOR_B_PWM_PIN, MOTOR_B_IN1_PIN, MOTOR_B_IN2_PIN, currentRight);
}

void stopMotors() {
  applyDrive(0, 0);
}

void initializeMotors() {
  pinMode(MOTOR_A_IN1_PIN, OUTPUT);
  pinMode(MOTOR_A_IN2_PIN, OUTPUT);
  pinMode(MOTOR_B_IN1_PIN, OUTPUT);
  pinMode(MOTOR_B_IN2_PIN, OUTPUT);
  ledcAttachChannel(MOTOR_A_PWM_PIN, MOTOR_PWM_FREQUENCY, MOTOR_PWM_RESOLUTION, MOTOR_PWM_CHANNEL_A);
  ledcAttachChannel(MOTOR_B_PWM_PIN, MOTOR_PWM_FREQUENCY, MOTOR_PWM_RESOLUTION, MOTOR_PWM_CHANNEL_B);
  stopMotors();
}

void sendMotorStatus() {
  StaticJsonDocument<192> status;
  status["type"] = "motor_status";
  status["status"] = (currentLeft == 0 && currentRight == 0) ? "stopped" : "moving";
  status["left"] = currentLeft;
  status["right"] = currentRight;
  status["mode"] = controlModeName();
  status["emergency"] = emergencyStopped;
  status["source"] = controlMode == ControlMode::AUTO ? "phone" : "web";
  String payload;
  serializeJson(status, payload);
  webSocket.broadcastTXT(payload);
}

void sendModeStatus() {
  StaticJsonDocument<96> status;
  status["type"] = "control_mode";
  status["mode"] = controlModeName();
  String payload;
  serializeJson(status, payload);
  webSocket.broadcastTXT(payload);
}

ClientSlot* slotForRole(const char* role) {
  if (strcmp(role, "web") == 0) return &webClient;
  if (strcmp(role, "phone") == 0) return &phoneClient;
  return nullptr;
}

ClientSlot* slotForClient(uint8_t clientId) {
  if (webClient.connected && webClient.clientId == clientId) return &webClient;
  if (phoneClient.connected && phoneClient.clientId == clientId) return &phoneClient;
  return nullptr;
}

void sendJson(uint8_t clientId, JsonDocument& document) {
  String payload;
  serializeJson(document, payload);
  webSocket.sendTXT(clientId, payload);
}

void sendAck(uint8_t clientId, const char* command, bool accepted, const char* error = nullptr) {
  StaticJsonDocument<256> response;
  response["type"] = "ack";
  response["command"] = command;
  response["accepted"] = accepted;
  if (error != nullptr) response["error"] = error;
  sendJson(clientId, response);
}

void sendDriveAck(uint8_t clientId, bool accepted, uint32_t sequence, const char* error = nullptr) {
  StaticJsonDocument<256> response;
  response["type"] = "ack";
  response["command"] = "drive";
  response["accepted"] = accepted;
  response["seq"] = sequence;
  if (error != nullptr) response["error"] = error;
  sendJson(clientId, response);
}

void sendError(uint8_t clientId, const char* error) {
  StaticJsonDocument<192> response;
  response["type"] = "error";
  response["error"] = error;
  sendJson(clientId, response);
}

void broadcastConnection(const char* target, bool connected) {
  StaticJsonDocument<192> message;
  message["type"] = "connection";
  message["target"] = target;
  message["state"] = connected ? "connected" : "disconnected";
  String payload;
  serializeJson(message, payload);
  webSocket.broadcastTXT(payload);

  if (strcmp(target, "phone") == 0) {
    StaticJsonDocument<96> phoneStatus;
    phoneStatus["type"] = "phone_status";
    phoneStatus["connected"] = connected;
    String phonePayload;
    serializeJson(phoneStatus, phonePayload);
    webSocket.broadcastTXT(phonePayload);
  }
}

void registerClient(uint8_t clientId, const char* role) {
  ClientSlot* slot = slotForRole(role);
  if (slot == nullptr) {
    sendError(clientId, "Unsupported client role");
    return;
  }

  if (slot->connected && slot->clientId != clientId) {
    webSocket.disconnect(slot->clientId);
    Serial.printf("Replaced %s client %u with client %u\n", role, slot->clientId, clientId);
  }

  slot->clientId = clientId;
  slot->connected = true;
  strncpy(slot->role, role, sizeof(slot->role) - 1);

  StaticJsonDocument<192> response;
  response["type"] = "ack";
  response["command"] = "hello";
  response["accepted"] = true;
  response["role"] = role;
  sendJson(clientId, response);

  broadcastConnection(role, true);
  Serial.printf("Registered %s client %u\n", role, clientId);
}

void handleMessage(uint8_t clientId, uint8_t* payload, size_t length) {
  DynamicJsonDocument message(8192);
  DeserializationError parseError = deserializeJson(message, payload, length);
  if (parseError) {
    sendError(clientId, "Invalid JSON message");
    Serial.printf("Rejected invalid JSON from client %u: %s\n", clientId, parseError.c_str());
    return;
  }

  const char* type = message["type"];
  if (type == nullptr) {
    sendError(clientId, "Message type is required");
    return;
  }

  if (strcmp(type, "hello") == 0) {
    const char* role = message["role"];
    if (!isValidRole(role)) {
      sendError(clientId, "Role must be web or phone");
      return;
    }
    registerClient(clientId, role);
    return;
  }

  if (slotForClient(clientId) == nullptr) {
    sendError(clientId, "Register with hello before sending commands");
    return;
  }

  ClientSlot* client = slotForClient(clientId);
  if (client == nullptr || client->role[0] == '\0') return;

  if (strcmp(type, "camera") == 0 ||
      strcmp(type, "target_select") == 0 ||
      strcmp(type, "phone_command") == 0 ||
      strcmp(type, "tracking_status") == 0 ||
      strcmp(type, "recording_status") == 0) {
    ClientSlot* peer = strcmp(client->role, "web") == 0 ? &phoneClient : &webClient;
    if (!peer->connected) {
      sendAck(clientId, type, false, "Phone/browser peer is not connected");
      return;
    }
    message["from"] = client->role;
    sendJson(peer->clientId, message);
    sendAck(clientId, type, true);
    return;
  }

  if (strcmp(type, "webrtc_offer_request") == 0 ||
      strcmp(type, "webrtc_signal") == 0) {
    const char* session = message["session"];
    if (session == nullptr || strlen(session) == 0 ||
        (strcmp(client->role, "web") != 0 && strcmp(client->role, "phone") != 0)) {
      sendAck(clientId, type, false, "WebRTC session is required");
      return;
    }

    ClientSlot* peer = strcmp(client->role, "web") == 0 ? &phoneClient : &webClient;
    if (!peer->connected) {
      sendAck(clientId, type, false, "WebRTC peer is not connected");
      return;
    }
    message["from"] = client->role;
    if (strcmp(type, "webrtc_offer_request") == 0) {
      message["type"] = "webrtc_offer_request";
    }
    sendJson(peer->clientId, message);
    sendAck(clientId, type, true);
    return;
  }

  if (strcmp(type, "control_mode") == 0) {
    const char* mode = message["mode"];
    const bool isWeb = strcmp(client->role, "web") == 0;
    const bool isPhone = strcmp(client->role, "phone") == 0;
    const bool validMode = mode != nullptr &&
        (strcmp(mode, "MANUAL") == 0 || strcmp(mode, "STOPPED") == 0 || strcmp(mode, "AUTO") == 0);
    if (!validMode || (strcmp(mode, "AUTO") == 0 && !isPhone) ||
        (strcmp(mode, "MANUAL") == 0 && !isWeb && !isPhone) ||
        (strcmp(mode, "STOPPED") == 0 && !isWeb && !isPhone)) {
      sendAck(clientId, type, false, "Mode is not permitted for this client role");
      return;
    }
    controlMode = strcmp(mode, "MANUAL") == 0
        ? ControlMode::MANUAL
        : strcmp(mode, "AUTO") == 0 ? ControlMode::AUTO : ControlMode::STOPPED;
    emergencyStopped = false;
    stopMotors();
    if (controlMode == ControlMode::AUTO) {
      hasPhoneSequence = false;
      activeDriveClient = clientId;
    } else if (controlMode == ControlMode::MANUAL) {
      activeDriveClient = clientId;
    }
    if (controlMode == ControlMode::STOPPED) stopMotors();
    sendAck(clientId, type, true);
    sendModeStatus();
    sendMotorStatus();
    return;
  }

  if (strcmp(type, "drive") == 0) {
    if (!message["left"].is<int>() || !message["right"].is<int>()) {
      sendAck(clientId, type, false, "Drive left and right must be integers from -100 to 100");
      return;
    }
    const bool isWeb = strcmp(client->role, "web") == 0;
    const bool isPhone = strcmp(client->role, "phone") == 0;
    if ((controlMode == ControlMode::MANUAL && !isWeb && !isPhone) ||
        (controlMode == ControlMode::AUTO && !isPhone) ||
        controlMode == ControlMode::STOPPED || emergencyStopped) {
      sendAck(clientId, type, false, "Drive command is not permitted in the current mode");
      return;
    }
    uint32_t sequence = message["seq"] | 0;
    if (controlMode == ControlMode::AUTO) {
      const int requestedLeft = message["left"].as<int>();
      const int requestedRight = message["right"].as<int>();
      if (abs(requestedLeft) > AUTONOMOUS_MAX_PERCENT ||
          abs(requestedRight) > AUTONOMOUS_MAX_PERCENT) {
        sendDriveAck(clientId, false, sequence, "Autonomous drive exceeds safety limit");
        return;
      }
      if (!message["seq"].is<uint32_t>() || (hasPhoneSequence && sequence <= lastPhoneSequence)) {
        sendDriveAck(clientId, false, sequence, "Stale or missing drive sequence");
        return;
      }
      hasPhoneSequence = true;
      lastPhoneSequence = sequence;
      activeDriveClient = clientId;
    }
    applyDrive(message["left"].as<int>(), message["right"].as<int>());
    lastDriveAt = millis();
    Serial.printf("Accepted drive client=%u role=%s seq=%lu requested=(%d,%d)\n",
                  clientId, client->role, static_cast<unsigned long>(sequence),
                  message["left"].as<int>(), message["right"].as<int>());
    if (controlMode == ControlMode::AUTO) sendDriveAck(clientId, true, sequence);
    else sendAck(clientId, type, true);
    sendMotorStatus();
    return;
  }

  if (strcmp(type, "emergency_stop") == 0) {
    emergencyStopped = true;
    controlMode = ControlMode::STOPPED;
    stopMotors();
    sendAck(clientId, type, true);
    sendModeStatus();
    sendMotorStatus();
    return;
  }

  if (strcmp(type, "heartbeat") == 0) {
    if (controlMode == ControlMode::AUTO && strcmp(client->role, "phone") == 0) {
      lastDriveAt = millis();
      sendAck(clientId, type, true);
    } else {
      sendAck(clientId, type, false, "Heartbeat is not active");
    }
    return;
  }

  sendAck(clientId, type, false, "Command is not implemented");
}

void handleWebSocketEvent(uint8_t clientId, WStype_t eventType, uint8_t* payload, size_t length) {
  switch (eventType) {
    case WStype_CONNECTED:
      Serial.printf("WebSocket client %u connected\n", clientId);
      break;
    case WStype_DISCONNECTED: {
      ClientSlot* slot = slotForClient(clientId);
      if (slot != nullptr) {
        slot->connected = false;
        char disconnectedRole[sizeof(slot->role)];
        strncpy(disconnectedRole, slot->role, sizeof(disconnectedRole));
        disconnectedRole[sizeof(disconnectedRole) - 1] = '\0';
        slot->role[0] = '\0';
        if (strcmp(disconnectedRole, "web") == 0 ||
            (strcmp(disconnectedRole, "phone") == 0 &&
             (controlMode == ControlMode::AUTO || controlMode == ControlMode::MANUAL))) {
          stopMotors();
          controlMode = ControlMode::STOPPED;
          emergencyStopped = false;
          hasPhoneSequence = false;
          activeDriveClient = 0;
          sendModeStatus();
          sendMotorStatus();
        }
        broadcastConnection(disconnectedRole, false);
        Serial.printf("WebSocket %s client %u disconnected\n", disconnectedRole, clientId);
      } else {
        Serial.printf("Unregistered WebSocket client %u disconnected\n", clientId);
      }
      break;
    }
    case WStype_TEXT:
      handleMessage(clientId, payload, length);
      break;
    default:
      break;
  }
}

void handleRoot() {
  if (!LittleFS.exists("/index.html")) {
    httpServer.send(200, "text/plain", "RoboCam ESP32 L298N firmware is running.");
    return;
  }
  File file = LittleFS.open("/index.html", "r");
  httpServer.streamFile(file, "text/html");
  file.close();
}

String contentTypeForPath(const String& path) {
  if (path.endsWith(".html")) return "text/html";
  if (path.endsWith(".css")) return "text/css";
  if (path.endsWith(".js")) return "application/javascript";
  if (path.endsWith(".json")) return "application/json";
  if (path.endsWith(".svg")) return "image/svg+xml";
  if (path.endsWith(".png")) return "image/png";
  if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
  if (path.endsWith(".ico")) return "image/x-icon";
  return "application/octet-stream";
}

void handleStaticFile() {
  String path = httpServer.uri();
  if (path == "/") path = "/index.html";
  if (!LittleFS.exists(path)) {
    httpServer.send(404, "text/plain", "Not found");
    return;
  }

  File file = LittleFS.open(path, "r");
  httpServer.streamFile(file, contentTypeForPath(path));
  file.close();
}

void handleNotFound() {
  if (httpServer.method() == HTTP_GET && LittleFS.exists("/index.html")) {
    handleStaticFile();
    return;
  }
  httpServer.send(404, "text/plain", "Not found");
}

void setupAccessPoint() {
  WiFi.mode(WIFI_AP);
  if (!WiFi.softAPConfig(AP_IP, AP_GATEWAY, AP_SUBNET)) {
    Serial.println("ERROR: failed to configure AP address");
  }
  if (!WiFi.softAP(AP_SSID, AP_PASSWORD)) {
    Serial.println("ERROR: failed to start Wi-Fi AP");
  }
  Serial.print("AP address: ");
  Serial.println(WiFi.softAPIP());
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println("\nRoboCam L298N firmware starting");

  if (!LittleFS.begin(true)) {
    Serial.println("WARNING: LittleFS unavailable; serving fallback HTTP page");
  }

  setupAccessPoint();
  initializeMotors();
  httpServer.on("/", HTTP_GET, handleRoot);
  httpServer.onNotFound(handleStaticFile);
  httpServer.begin();

  webSocket.begin();
  webSocket.onEvent(handleWebSocketEvent);
  Serial.printf("HTTP server: http://%s/\n", AP_IP.toString().c_str());
  Serial.printf("WebSocket server: ws://%s:%u/ws\n", AP_IP.toString().c_str(), WS_PORT);
  Serial.println("Motor control ready; select MANUAL before sending drive commands.");
}

void loop() {
  httpServer.handleClient();
  webSocket.loop();
  if ((controlMode == ControlMode::MANUAL || controlMode == ControlMode::AUTO) &&
      (millis() - lastDriveAt > DRIVE_WATCHDOG_MS)) {
    if (currentLeft != 0 || currentRight != 0) {
      stopMotors();
      sendMotorStatus();
      Serial.println("Drive watchdog stopped motors");
    }
  }
}
