#include <AccelStepper.h>

const int stepPin1 = 2;
const int dirPin1  = 3;
const int stepPin2 = 4;
const int dirPin2  = 5;

const int limitPin1 = 7;
const int limitPin2 = 6;

AccelStepper stepper1(AccelStepper::DRIVER, stepPin1, dirPin1);
AccelStepper stepper2(AccelStepper::DRIVER, stepPin2, dirPin2);

void homeOne(AccelStepper &stepper, int limitPin) {
  stepper.setMaxSpeed(800);
  stepper.setAcceleration(400);

  stepper.moveTo(-100000);  // move toward switch

  while (digitalRead(limitPin) == 0) {  // move UNTIL switch == 1
    stepper.run();
  }

  stepper.stop();
  stepper.setCurrentPosition(0);
}

void setup() {
  pinMode(limitPin1, INPUT);
  pinMode(limitPin2, INPUT);

  Serial.begin(9600);

  homeOne(stepper1, limitPin1);
  homeOne(stepper2, limitPin2);

  Serial.println("Homed");
}

void loop() {}