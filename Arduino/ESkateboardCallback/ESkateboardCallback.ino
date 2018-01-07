#include <CurieBLE.h>
#include <Servo.h>

Servo mServo;

/*
 * Constants
 */
const int ledPin = 13; // set ledPin to use on-board LED
const String controllerMacAddr = "6E:15:48:80:B3:B9"; //4F:BA:4B:6C:28:6F
const int minPulseRate = 1000;
const int maxPulseRate = 2000;
const int neutralSpeed = 10;

/*
 * Statics
 */
int pwmPin = 9;
unsigned long startTime;
bool flag_connected = false;
// create service
BLEService ledService("19B10000-E8F2-537E-4F6C-D104768A1214"); 
// create switch characteristic and allow remote device to read and write
BLECharCharacteristic switchChar("19B10001-E8F2-537E-4F6C-D104768A1214", BLERead | BLEWrite);

/*
 * Code
 */
void setup() {
  Serial.begin(9600);
  pinMode(ledPin, OUTPUT); // use the LED on pin 13 as an output
  mServo.attach(pwmPin, minPulseRate, maxPulseRate);

  // begin initialization
  BLE.begin();

  // set the local name peripheral advertises
  BLE.setLocalName("ESkater");
  // set the UUID for the service this peripheral advertises
  BLE.setAdvertisedService(ledService);

  // add the characteristic to the service
  ledService.addCharacteristic(switchChar);

  // add service
  BLE.addService(ledService);

  // assign event handlers for connected, disconnected to peripheral
  BLE.setEventHandler(BLEConnected, blePeripheralConnectHandler);
  BLE.setEventHandler(BLEDisconnected, blePeripheralDisconnectHandler);

  // assign event handlers for characteristic
  switchChar.setEventHandler(BLEWritten, switchCharacteristicWritten);
  // set an initial value for the characteristic
  switchChar.setValue(0);

  // start advertising
  BLE.advertise();

  Serial.println(("Bluetooth device active, waiting for connections..."));
  startTime = millis();
  
  mServo.write(1500);
}

void loop() {
  // poll for BLE events
  // Serial.println(("polling"));
  BLE.poll();

  // as long as ble is not connected, blink LED with 500ms interval
  if (!flag_connected) {
    unsigned long curTime = millis();
    if ((curTime - startTime) > 500) {
      digitalWrite(ledPin, !digitalRead(ledPin));
      startTime = curTime;
    } 
  }
}

void blePeripheralConnectHandler(BLEDevice central) {
  // central connected event handler
  // Serial.print("Connected event, central: ");
  // String devAddr = central.address();
  // Serial.println(devAddr);
  /*
  if(!devAddr.equals(controllerMacAddr)) {
    central.disconnect();
    BLE.advertise();
    Serial.print("Invalid central, disconnect");
    return;
  }*/
  flag_connected = true;
  digitalWrite(ledPin, HIGH);
}

void blePeripheralDisconnectHandler(BLEDevice central) {
  // central disconnected event handler
  // Serial.print("Disconnected event, central: ");
  // Serial.println(central.address());
  mServo.write(1000);
  flag_connected = false;
  digitalWrite(ledPin, LOW);
}

void switchCharacteristicWritten(BLEDevice central, BLECharacteristic characteristic) {
  // central wrote new value to characteristic, update LED
  // Serial.print("Characteristic event, written: ");
  int pwmVal;
  if (switchChar.value() >= neutralSpeed) {
    // map from 10-100(0-90) to 1500-1950
    pwmVal = ((switchChar.value() - neutralSpeed) * 5) + 1500;
  } else {
    // map from 0-10(-10-0) to 1000-1500
    pwmVal = ((switchChar.value() - neutralSpeed) * 50) + 1500;
  }
  // Serial.println(pwmVal);
  mServo.write(pwmVal);
}
