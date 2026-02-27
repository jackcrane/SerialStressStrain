#include <AccelStepper.h>

const int stepPin1 = 2;
const int dirPin1  = 3;
const int stepPin2 = 4;
const int dirPin2  = 5;

const int limitPin1 = 7;
const int limitPin2 = 6;

AccelStepper stepper1(AccelStepper::DRIVER, stepPin1, dirPin1);
AccelStepper stepper2(AccelStepper::DRIVER, stepPin2, dirPin2);

void homeBoth() {

  stepper1.setMaxSpeed(10000);
  stepper1.setAcceleration(400);

  stepper2.setMaxSpeed(10000);
  stepper2.setAcceleration(400);

  // Move toward switches (flip sign if wrong direction)
  stepper1.moveTo(-100000);
  stepper2.moveTo(-100000);

  bool homed1 = false;
  bool homed2 = false;

  while (!homed1 || !homed2) {
    if (!homed1) {
      if (digitalRead(limitPin1) == 1) {
        stepper1.stop();
        stepper1.setCurrentPosition(0);
        homed1 = true;
      } else {
        stepper1.run();
      }
    }

    if (!homed2) {
      if (digitalRead(limitPin2) == 1) {
        stepper2.stop();
        stepper2.setCurrentPosition(0);
        homed2 = true;
      } else {
        stepper2.run();
      }
    }
  }
}

void setup() {
  pinMode(limitPin1, INPUT);
  pinMode(limitPin2, INPUT);

  pinMode(limitPin1, INPUT_PULLUP);
  pinMode(limitPin2, INPUT_PULLUP);

  Serial.begin(9600);

  homeBoth();

  Serial.println("Homed");
}

void loop() {}