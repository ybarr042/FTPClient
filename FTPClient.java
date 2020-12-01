

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class FTPClient {
    private enum FTPCommands {
        OPTS,
        USER,
        PASS,
        HELP,
        RETR,
        NLST,
        PASV,
        STOR,
        CWD,
        DELE,
        PWD,
        LIST
    }

    private static class SocketClient {
        final String EOL = "\r\n";
        private final Socket socket;
        private final BufferedWriter serverSender;
        private final BufferedReader serverReceiver;
        private final String hostname;
        private int port;

        public SocketClient(String host, int port) throws IOException {
            this.hostname = host;
            this.port = port;
            this.socket = new Socket(this.hostname, this.port);           
            serverSender = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            serverReceiver = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        private void sendCommandToServer(FTPCommands command, String data) throws IOException {
            this.serverSender.write(command.name() + " " + data + this.EOL);
            this.serverSender.flush();
        }

        private void sendCommandToServer(FTPCommands command) throws IOException {
            this.serverSender.write(command.name() + this.EOL);
            this.serverSender.flush();
        }

        private String getServerLine() throws IOException {
            return serverReceiver.readLine();
        }

        public void close() {
            try {
                this.serverSender.close();
                this.serverReceiver.close();
                this.socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private static class FTPProtocolClient {
        private final SocketClient client;
        private final BufferedReader keyboard;
        private String username = null;

        public FTPProtocolClient(SocketClient client) {
            this.client = client;
            this.keyboard = new BufferedReader(new InputStreamReader(System.in));
        }

        public void connect() throws IOException {
            String data = this.client.getServerLine();
            System.out.println(data);
            this.client.sendCommandToServer(FTPCommands.OPTS, "UTF8 ON");
            data = this.client.getServerLine();
            System.out.println(data);
        }

        public void login() throws IOException {
            boolean successfulConnection;
            do {
                // Get input from user
                System.out.println("Enter your user:");
                this.username = keyboard.readLine();
                this.client.sendCommandToServer(FTPCommands.USER, this.username);
                this.client.getServerLine();

                // Send the password
                System.out.println("Enter your password:");
                String password = keyboard.readLine();
                this.client.sendCommandToServer(FTPCommands.PASS, password);
                String response = this.client.getServerLine();
                System.out.println(response);

                successfulConnection = response.startsWith("530");
            }
            while (successfulConnection);

        }

        private SocketClient getPassiveConnection() throws IOException {
            this.client.sendCommandToServer(FTPCommands.PASV);
            String response = this.client.getServerLine();
            System.out.println(response);         

            //Get the hostname and port from the server

            String[] spaceSeparated = response.split("(,|\\.|\\)|\\()");

            int passivePort = Integer.parseInt(spaceSeparated[5]) * 256 + Integer.parseInt(spaceSeparated[6]);

            String passiveHostname = spaceSeparated[1];
            for (int i = 2; i < spaceSeparated.length - 2; i++) {
                passiveHostname += "." + spaceSeparated[i];
            }

            //Create the new SocketClient
            return new SocketClient(passiveHostname, passivePort);
        }

        public void ls(String arguments) throws IOException {
            SocketClient lsSocket = getPassiveConnection();

            this.client.sendCommandToServer(FTPCommands.LIST);
            System.out.println(this.client.getServerLine());

            String response = lsSocket.getServerLine();

            while (response != null) {
                System.out.println(response);
                response = lsSocket.getServerLine();
            }

            System.out.println(this.client.getServerLine());
            lsSocket.close();
        }

        public void cd(String arguments) throws IOException {
            // send request for the file
            this.client.sendCommandToServer(FTPCommands.CWD, arguments);
            System.out.println(this.client.getServerLine());
        }

        public void get(String arguments) throws IOException {
            SocketClient dataSocket = getPassiveConnection();

            // send request for the file
            this.client.sendCommandToServer(FTPCommands.RETR, arguments);
            System.out.println(this.client.getServerLine());

            // open file stream
            FileWriter writer = new FileWriter(arguments);

            // write remote stream to local stream
            final int MAX_BUFFER_SIZE = 4096;
            char[] buffer = new char[MAX_BUFFER_SIZE];
            int count;

            while (true) {
                count = dataSocket.serverReceiver.read(buffer, 0, MAX_BUFFER_SIZE);
                if (count > 0) {
                    writer.write(buffer, 0, count);
                } else {
                    break;
                }
            }
            System.out.println(this.client.getServerLine());

            // close sockets
            dataSocket.close();
            writer.close();
        }

        public void put(String arguments) throws IOException {
            SocketClient dataSocket = getPassiveConnection();

            // send request for the file
            this.client.sendCommandToServer(FTPCommands.STOR, arguments);
            System.out.println(this.client.getServerLine());

            // open file stream
            FileReader reader = new FileReader(arguments);

            // write remote stream to local stream
            final int MAX_BUFFER_SIZE = 4096;
            char[] buffer = new char[MAX_BUFFER_SIZE];
            int count;

            while (true) {
                count = reader.read(buffer, 0, MAX_BUFFER_SIZE);
                if (count > 0) {
                    dataSocket.serverSender.write(buffer, 0, count);
                } else {
                    break;
                }
            }
            // close sockets
            dataSocket.close();
            System.out.println(this.client.getServerLine());
        }

        public void help() throws IOException {
            this.client.sendCommandToServer(FTPCommands.HELP);
            String response;
            int count = 0;
            do {
                response = this.client.getServerLine();
                System.out.println(response);
                if (response.startsWith("214")) {
                    count++;
                }
            }
            while (count < 2);
        }

        public void delete(String arguments) throws IOException {
            this.client.sendCommandToServer(FTPCommands.DELE, arguments);
            System.out.println(this.client.getServerLine());
        }

        public void processCommands() {
            String inputString = "";
            while (!inputString.equals("quit")) {
                try {
                    this.showPrompt();
                    inputString = keyboard.readLine();
                    this.dispatchCommand(inputString);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            this.disconnect();
        }

        private void showPrompt() throws IOException {
            this.client.sendCommandToServer(FTPCommands.PWD);
            String currentPath = this.client.getServerLine().split("\"")[1];
            System.out.print(this.username + "@" + this.client.hostname + ":" + currentPath + "> ");
        }

        public void dispatchCommand(String line) throws IOException {
            String[] spaceSeparated = line.split(" ");
            int startIndex = spaceSeparated[0].length();
            if (startIndex < line.length()) {
                startIndex += 1;
            }
            String arguments = line.substring(startIndex);
            switch (spaceSeparated[0].toLowerCase()) {
                case "ls":
                    this.ls(arguments);
                    break;
                case "get":
                    this.get(arguments);
                    break;
                case "put":
                    this.put(arguments);
                    break;
                case "cd":
                    this.cd(arguments);
                    break;
                case "help":
                    this.help();
                    break;
                case "delete":
                    this.delete(arguments);
                    break;
                case "quit":
                    break;
                default:
                    System.out.println("Command not supported");
                    break;
            }
        }

        public void disconnect() {
            System.out.println("Closing connection with: " + this.client.hostname);
            this.client.close();
        }

    }

    public static void main(String[] args) {
        try {
            // starting the connection to the server.
//            System.out.println("Enter myftp server-name:");
//            String hostname = "inet.cs.fiu.edu";
            String hostname = args[0];
            int port = 21;

            SocketClient socketClient = new SocketClient(hostname, port);
            FTPProtocolClient protocolClient = new FTPProtocolClient(socketClient);

            protocolClient.connect();
            protocolClient.login();
            protocolClient.processCommands();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
