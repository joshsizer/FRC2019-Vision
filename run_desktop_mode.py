import os
import subprocess

# builds and runs the project

file = 'build/libs/FRC2019 - Vision-all.jar'
os.system('gradlew build')
os.system('java -jar "' + file + '"' + ' -desktop')