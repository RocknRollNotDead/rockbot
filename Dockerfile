FROM eclipse-temurin:21-jdk

WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy Maven files first for better caching
COPY pom.xml .

# Download dependencies with increased timeout
RUN mvn dependency:go-offline -B -Dmaven.wagon.http.retryHandler.count=3 || true

# Copy source code
COPY src ./src

# Build the application with minimal logging
RUN mvn clean package -DskipTests -B --quiet

# Run the application
CMD ["java", "-jar", "target/rock-band-bot-1.0-SNAPSHOT.jar"]
