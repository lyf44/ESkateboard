/*
 * Copyright (c) 2016 Intel Corporation.  All rights reserved.
 * See the bottom of this file for the license terms.
 */

#include <CurieBLE.h>
#include <Servo.h>

Servo mServo;

const int ledPin = 13; // set ledPin to use on-board LED
int pwmPin = 9;
unsigned long startTime;
bool flag_connected = false;
int minPulseRate = 1000;
int maxPulseRate = 2000;
const String controllerMacAddr = "6E:15:48:80:B3:B9"; //4F:BA:4B:6C:28:6F


BLEService ledService("19B10000-E8F2-537E-4F6C-D104768A1214"); // create service

// create switch characteristic and allow remote device to read and write
BLECharCharacteristic switchChar("19B10001-E8F2-537E-4F6C-D104768A1214", BLERead | BLEWrite);

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
  
  mServo.write(90);
}

void loop() {
  // poll for BLE events
  // Serial.println(("polling"));
  BLE.poll();
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
  mServo.write(0);
  flag_connected = false;
  digitalWrite(ledPin, LOW);
}

void switchCharacteristicWritten(BLEDevice central, BLECharacteristic characteristic) {
  // central wrote new value to characteristic, update LED
  // Serial.print("Characteristic event, written: ");
  int pwmVal;
  if (switchChar.value()) {
    pwmVal = (switchChar.value() * 5) + 1500;
  } else {
    pwmVal = 0;
  }
  // Serial.println(pwmVal);
  mServo.write(pwmVal);
}

/*
  Copyright (c) 2016 Intel Corporation. All rights reserved.

  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR P000000URPOSE. See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public
  License along with this library; if not, write to the Free Software
  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-
  1301 USA
*/
