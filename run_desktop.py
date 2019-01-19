import os
import subprocess

# builds and runs the project

jarFile = 'build/libs/FRC2019-Vision-all.jar'
imagePath = os.path.abspath('images/')
allImages = os.listdir(imagePath)

os.system('gradlew build')

for file in allImages:
    if file.endswith(".jpg"):
        os.system('java -jar ' + jarFile +
                  ' -desktop -images ' + imagePath + '/' + file)
