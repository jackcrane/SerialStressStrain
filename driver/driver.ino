const int stepPin1 = 2;
const int dirPin1  = 3;
const int stepPin2 = 4;
const int dirPin2  = 5;

const int limitPin1 = 7;
const int limitPin2 = 6;

// Smaller = faster
const int stepDelayMicrosHome = 25;   // try 100, 75, 50 if your driver allows
const int stepDelayMicros = 100;
const int moveSteps = 8000;

void homeBoth() {
  digitalWrite(dirPin1, LOW);
  digitalWrite(dirPin2, LOW);

  bool homed1 = false;
  bool homed2 = false;

  while (!homed1 || !homed2) {

    if (!homed1 && digitalRead(limitPin1) == HIGH) {
      homed1 = true;
    }

    if (!homed2 && digitalRead(limitPin2) == HIGH) {
      homed2 = true;
    }

    if (!homed1) digitalWrite(stepPin1, HIGH);
    if (!homed2) digitalWrite(stepPin2, HIGH);

    delayMicroseconds(stepDelayMicrosHome);

    if (!homed1) digitalWrite(stepPin1, LOW);
    if (!homed2) digitalWrite(stepPin2, LOW);

    delayMicroseconds(stepDelayMicrosHome);
  }
}

const float STEPS_PER_MM = 1600.0;   // adjust if different microstepping

void moveUp(float mm) {
  int steps = (int)(mm * STEPS_PER_MM);
  stepBoth(steps, false);
}

void moveDown(float mm) {
  int steps = (int)(mm * STEPS_PER_MM);
  stepBoth(steps, true);
}

void stepBoth(int steps, bool directionUp) {

  digitalWrite(dirPin1, directionUp ? HIGH : LOW);
  digitalWrite(dirPin2, directionUp ? HIGH : LOW);

  for (int i = 0; i < steps; i++) {

    // Optional safety: don't move downward past limits
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
  }
}

void setup() {
  pinMode(stepPin1, OUTPUT);
  pinMode(dirPin1, OUTPUT);
  pinMode(stepPin2, OUTPUT);
  pinMode(dirPin2, OUTPUT);

  pinMode(limitPin1, INPUT_PULLUP);
  pinMode(limitPin2, INPUT_PULLUP);

  Serial.begin(9600);

  homeBoth();
}

void loop() {

  if (!Serial.available()) return;

  String input = Serial.readStringUntil('\n');
  input.trim();

  int firstComma = input.indexOf(',');
  if (firstComma == -1) return;

  int tool = input.substring(0, firstComma).toInt();


  int secondComma = input.indexOf(',', firstComma + 1);

  // ---- HOME ----
  if (tool == 1) {
    homeBoth();
    return;
  }

  // ---- MOVE ----
  if (tool == 0 && secondComma != -1) {

    int direction = input.substring(firstComma + 1, secondComma).toInt();
    float distance = input.substring(secondComma + 1).toFloat();

    if (direction == 1) moveUp(distance);
    if (direction == -1) moveDown(distance);
  }
}