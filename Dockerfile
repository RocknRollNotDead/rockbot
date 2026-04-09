FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copy Maven files first for better caching
COPY pom.xml .

# Download dependencies (this layer will be cached)
RUN apt-get update && apt-get install -y maven && \
    mvn dependency:go-offline -B || true

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B -e -X

# Run the application
CMD ["java", "-jar", "target/rock-band-bot-1.0-SNAPSHOT.jar"]
