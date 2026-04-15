import subprocess
import time

programs = [
    # Arduino programs
    ("arduino-crystal-ball/upload.wasm", -1, 4, -1),
    ("arduino-knock/upload.wasm",-1, 1, -1),
    ("arduino-touch-sensor-lamp/upload.wasm", 500, 1, -1),
    ("arduino-switch-example/upload.wasm", 500, 1, -1),
    ("arduino-love-o-meter/upload.wasm", -1, 1, -1),
    ("arduino-keyboard/upload.wasm", -1, 1, -1),    
    ("arduino-while-no-calibrate/upload.wasm", 100, 10, 200),
    ("arduino-while-example/upload.wasm", 100, 10, 200),
    ("arduino-knock-lock/upload.wasm", -1, 4, -1),
    ("arduino-zoetrope/upload.wasm", -1, 6, -1),
    
    # Custom
    ("gesture-robot/upload.wasm", -1, 10, -1),
    ("breakout/upload.wasm", -1, 259, -1)
]

wdcli = "../WARDuino-symbolic/build-emu/wdcli"

def analyse_state_space():
    for program in programs:
        print(f"Running {program[0]}", end="", flush=True)
        command = wdcli + " " + program[0] + f" --no-socket --no-debug --mode concolic --max-instructions {program[1]} --max-symbolic-variables {program[2]} --max-iterations {program[3]}"
        #print(command)
        #input("Press any key...")
        #process = subprocess.run([command], shell=True, check=True)
        process = subprocess.run([command], shell=True, check=True, capture_output=True, text=True)
        output_lines = process.stdout.strip().split('\n')
        print(f"\r{program[0].replace("/upload.wasm", "")} & {output_lines[-1]}")

analyse_state_space()
