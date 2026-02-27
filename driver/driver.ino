unsigned long lastReportTime = 0;
const unsigned long reportIntervalMs = 20;

long currentPositionSteps = 0;

const int stepPin1 = 2;
const int dirPin1  = 3;
const int stepPin2 = 4;
const int dirPin2  = 5;

const int limitPin1 = 7;
const int limitPin2 = 6;

const int stepDelayMicrosHome = 25;
const int stepDelayMicros = 50;

const float STEPS_PER_MM = 1600.0;

// ---- SOFTWARE LIMIT ----
const float MAX_TRAVEL_MM = 80.0;
const long MAX_TRAVEL_STEPS = (long)(MAX_TRAVEL_MM * STEPS_PER_MM);

void reportNow() {
  float positionMM = currentPositionSteps / STEPS_PER_MM;
  int a6Value = analogRead(A6);

  Serial.print("0,");
  Serial.print(positionMM, 4);
  Serial.print(",");
  Serial.print(a6Value);
  Serial.print("\n");
}

void homeBoth() {

  digitalWrite(dirPin1, LOW);
  digitalWrite(dirPin2, LOW);

  bool homed1 = false;
  bool homed2 = false;

  while (!homed1 || !homed2) {

    if (!homed1 && digitalRead(limitPin1) == HIGH) homed1 = true;
    if (!homed2 && digitalRead(limitPin2) == HIGH) homed2 = true;

    if (!homed1) digitalWrite(stepPin1, HIGH);
    if (!homed2) digitalWrite(stepPin2, HIGH);

    delayMicroseconds(stepDelayMicrosHome);

    if (!homed1) digitalWrite(stepPin1, LOW);
    if (!homed2) digitalWrite(stepPin2, LOW);

    delayMicroseconds(stepDelayMicrosHome);
  }

  currentPositionSteps = 0;
}

void moveUp(float mm) {
  long steps = (long)(mm * STEPS_PER_MM);
  stepBoth(steps, false);
}

void moveDown(float mm) {
  long steps = (long)(mm * STEPS_PER_MM);
  stepBoth(steps, true);
}

void stepBoth(long steps, bool directionUp) {

  digitalWrite(dirPin1, directionUp ? HIGH : LOW);
  digitalWrite(dirPin2, directionUp ? HIGH : LOW);

  for (long i = 0; i < steps; i++) {

    // ---- HARD LIMIT CHECK ----
    if (directionUp && currentPositionSteps >= MAX_TRAVEL_STEPS) {
      break;
    }

    if (!directionUp) {
      if (digitalRead(limitPin1) == HIGH || digitalRead(limitPin2) == HIGH) {
        break;
      }
    }

    digitalWrite(stepPin1, HIGH);
    digitalWrite(stepPin2, HIGH);
    delayMicroseconds(stepDelayMicros);

    digitalWrite(stepPin1, LOW);
    digitalWrite(stepPin2, LOW);
    delayMicroseconds(stepDelayMicros);

    if (directionUp) currentPositionSteps++;
    else currentPositionSteps--;

    unsigned long now = millis();
    if (now - lastReportTime >= reportIntervalMs) {
      lastReportTime = now;
      reportNow();
    }
  }
}

void moveToFirstTouch() {

  const int touchThreshold = 10;

  // Move in positive direction
  digitalWrite(dirPin1, HIGH);
  digitalWrite(dirPin2, HIGH);

  while (true) {

    // Stop if we hit software max travel
    if (currentPositionSteps >= MAX_TRAVEL_STEPS) break;

    int load = analogRead(A6);
    if (load > touchThreshold) break;

    digitalWrite(stepPin1, HIGH);
    digitalWrite(stepPin2, HIGH);
    delayMicroseconds(stepDelayMicrosHome);

    digitalWrite(stepPin1, LOW);
    digitalWrite(stepPin2, LOW);
    delayMicroseconds(stepDelayMicrosHome);

    currentPositionSteps++;

    unsigned long now = millis();
    if (now - lastReportTime >= reportIntervalMs) {
      lastReportTime = now;
      reportNow();
    }
  }

  moveUp(3.0);
}

void setup() {
  pinMode(stepPin1, OUTPUT);
  pinMode(dirPin1, OUTPUT);
  pinMode(stepPin2, OUTPUT);
  pinMode(dirPin2, OUTPUT);

  pinMode(limitPin1, INPUT_PULLUP);
  pinMode(limitPin2, INPUT_PULLUP);

  Serial.begin(115200);

  homeBoth();
}

void loop() {

  unsigned long now = millis();
  if (now - lastReportTime >= reportIntervalMs) {
    lastReportTime = now;
    reportNow();
  }

  if (!Serial.available()) return;

  String input = Serial.readStringUntil('\n');
  input.trim();

  int firstComma = input.indexOf(',');
  if (firstComma == -1) return;

  int tool = input.substring(0, firstComma).toInt();
  int secondComma = input.indexOf(',', firstComma + 1);

  if (tool == 1) {
    homeBoth();
    return;
  }

  if (tool == 2) {
    moveToFirstTouch();
    return;
  }

  if (tool == 0 && secondComma != -1) {

    int direction = input.substring(firstComma + 1, secondComma).toInt();
    float distance = input.substring(secondComma + 1).toFloat();

    if (direction == 1) moveUp(distance);
    if (direction == -1) moveDown(distance);
  }
}