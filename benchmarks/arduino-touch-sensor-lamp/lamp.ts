@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;
//@external("env", "chip_capacitive_sensor") declare function chip_capacitive_sensor(sendPin: i32, receivePin: i32, samples: i32): i32;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;

function print(text: string): void {
    //print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

const OUTPUT = 2;

enum Pin {
    sendPin = 4,
    receivePin = 2,
    ledPin = 12,
}

let threshold: i32 = 1000;

export function main(): void {
    chip_pin_mode(Pin.ledPin, OUTPUT);

    while (true) {
        // store the value reported by the sensor in a variable
        //let sensorValue: i32 = chip_capacitive_sensor(Pin.sendPin, Pin.receivePin, 30);
        let sensorValue: i32 = chip_analog_read(Pin.sendPin);

        // print out the sensor value
        //print(sensorValue.toString() + "\n");

        // if the value is greater than the threshold
        if (sensorValue > threshold) {
            // turn the LED on
            chip_digital_write(Pin.ledPin, 1);
        } else {
            // turn the LED off
            chip_digital_write(Pin.ledPin, 0);
        }

        chip_delay(10);
    }
}
