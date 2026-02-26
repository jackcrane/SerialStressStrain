// limits=7,6

const int stepPin1 = 2;
const int dirPin1  = 3;

const int stepPin2 = 4;
const int dirPin2  = 5;

unsigned long lastStepTime1 = 0;
unsigned long lastStepTime2 = 0;

const unsigned long stepInterval1 = 50;  // microseconds (speed motor 1)
const unsigned long stepInterval2 = 50; // microseconds (speed motor 2)

bool stepState1 = HIGH;
bool stepState2 = HIGH;

void setup() {
  pinMode(stepPin1, OUTPUT);
  pinMode(dirPin1, OUTPUT);

  pinMode(stepPin2, OUTPUT);
  pinMode(dirPin2, OUTPUT);

  digitalWrite(dirPin1, HIGH); // direction motor 1
  digitalWrite(dirPin2, HIGH);  // direction motor 2
}

void loop() {
  unsigned long currentMicros = micros();

  // Motor 1
  if (currentMicros - lastStepTime1 >= stepInterval1) {
    lastStepTime1 = currentMicros;
    stepState1 = !stepState1;
    digitalWrite(stepPin1, stepState1);
  }

  // Motor 2
  if (currentMicros - lastStepTime2 >= stepInterval2) {
    lastStepTime2 = currentMicros;
    stepState2 = !stepState2;
    digitalWrite(stepPin2, stepState2);
  }
}