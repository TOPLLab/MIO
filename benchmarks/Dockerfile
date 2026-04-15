FROM eclipse-temurin:21-jdk
RUN apt update
RUN apt install build-essential cmake libz3-dev git python3 -y
WORKDIR /app
RUN git config --global url."https://github.com/".insteadOf git@github.com:
RUN git clone --recursive https://github.com/TOPLLab/MIO.git -b verder
WORKDIR /app/MIO
RUN ./gradlew setup
WORKDIR /app/MIO/benchmarks
CMD ["python3", "test.py"]
