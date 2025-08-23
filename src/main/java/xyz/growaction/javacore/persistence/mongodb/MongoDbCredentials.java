/*
 * Copyright (c) 2024-2025 nordtal.eu.
 *
 * Licensed under the Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International License (CC BY-NC-ND 4.0).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.growaction.javacore.persistence.mongodb;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * This record holds credentials for a MongoDB database connection
 * 
 * @param hostname the hostname of the database server
 * @param port the port of the database server
 * @param username the username to authenticate with - can be null
 * @param password the password to authenticate with - can be null
 * @param database the database within the database server to use
 * @param entityClass the entity class of this repository, equal to T
 * @param <T> the type of entity this repository is handling
 */
@Builder
public record MongoDbCredentials<T>(@NotNull String hostname, int port, String username, String password, @NotNull String database, @NotNull Class<T> entityClass) {

    /**
     * Specifies the protocol of a MongoDB connection URI
     */
    private static final String MONGODB_PROTOCOL = "mongodb://";

    /**
     * Builds a URI {@link String} from the credentials
     *
     * @return the URI as {@link String}
     */
    public String getUriString() {
        final StringBuilder uriBuilder = new StringBuilder(MONGODB_PROTOCOL);

        final boolean usernamePresent = username != null && !username.isBlank();
        final boolean passwordPresent = password != null && !password.isBlank();

        // Append username is present append
        if (usernamePresent) {
            uriBuilder.append(URLEncoder.encode(username, StandardCharsets.UTF_8));
        }

        // If username or password are present append the separator
        if (usernamePresent || passwordPresent) {
            uriBuilder.append(":");
        }

        // If password is present append
        if (passwordPresent) {
            uriBuilder.append(URLEncoder.encode(password, StandardCharsets.UTF_8));
        }

        // If username or password are present append auth / host separator
        if (usernamePresent || passwordPresent) {
            uriBuilder.append("@");
        }

        // Append hostname and port
        uriBuilder.append(hostname).append(":").append(port);

        // Build and return
        return uriBuilder.toString();
    }

}
