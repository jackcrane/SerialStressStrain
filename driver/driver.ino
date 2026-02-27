const int stepPin1 = 2;
const int dirPin1  = 3;
const int stepPin2 = 4;
const int dirPin2  = 5;

const int limitPin1 = 7;
const int limitPin2 = 6;

// Smaller = faster
const int stepDelayMicros = 25;   // try 100, 75, 50 if your driver allows

void homeBoth() {

  digitalWrite(dirPin1, LOW);   // homing direction
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

    // Step motor 1
    if (!homed1) {
      digitalWrite(stepPin1, HIGH);
    }

    // Step motor 2
    if (!homed2) {
      digitalWrite(stepPin2, HIGH);
    }

    delayMicroseconds(stepDelayMicros);

    if (!homed1) {
      digitalWrite(stepPin1, LOW);
    }

    if (!homed2) {
      digitalWrite(stepPin2, LOW);
    }

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

  Serial.println("Homed");
}

void loop() {}