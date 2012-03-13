package net.krinsoft.irc;

import net.krinsoft.irc.events.IRCJoinEvent;
import net.krinsoft.irc.events.IRCMessageEvent;
import net.krinsoft.irc.events.IRCQuitEvent;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author krinsdeath
 */
public class Connection {
    /**
     * The IRC handler for this connection
     */
    private IRCBot                  manager;

    /**
     * Nickname parser for String consts
     */
    private final static Pattern    NICK            = Pattern.compile("\\[nick\\]");

    /**
     * This connection's socket for I/O
     */
    private volatile Socket         connection;

    /**
     * Input Stream from Socket
     */
    private BufferedReader          bReader;

    /**
     * Output stream from Socket
     */
    private BufferedWriter          bWriter;

    /**
     * Reader thread for this connection
     */
    private volatile IRCReader      reader;

    /**
     * Writer thread for this connection
     */
    private volatile IRCWriter      writer;

    /**
     * The network name for this connection
     */
    private String                  network;

    /**
     * This connection's hostname
     */
    private String                  hostname;

    /**
     * This connection's port number
     */
    private int                     port;

    /**
     * This connection's main channel (global)
     */
    private String                  channel;

    /**
     * This connection's channel key (optional)
     */
    private String                  key;

    /**
     * A list of lines to be written to this connection's socket
     */
    private List<String>            lines           = new ArrayList<String>();

    private class IRCReader extends Thread {
        private Connection master;

        public IRCReader(Connection conn, String name) {
            super(name);
            master = conn;
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    readIRC();
                    sleep(200);
                } catch (InterruptedException e) {
                    throw new ThreadCleanupException("IRCReader cleaned up successfully!");
                }
            }
        }

        public void readIRC() {
            try {
                String line;
                while ((line = bReader.readLine()) != null) {
                    if (line.startsWith("PING")) {
                        writeLine("PONG " + line.substring(5));
                        continue;
                    }
                    String nick, message, target = null;
                    Reply reply = Reply.get(line.split(" ")[1]);
                    if (reply == null) { continue; }
                    switch (reply) {
                        case RPL_MYINFO:
                            writeLine("JOIN " + channel + " " + key);
                            auth();
                            chanMsg(channel, manager.STARTUP);
                            break;
                        case JOIN:
                            nick = line.substring(1).split("!")[0];
                            if (nick.equalsIgnoreCase(manager.NICKNAME) || manager.isOnline(nick)) { break; }
                            manager.setOnline(nick, true);
                            IRCJoinEvent join = new IRCJoinEvent(NICK.matcher(manager.PLAYER_JOIN_IRC).replaceAll(nick));
                            manager.getPlugin().getServer().getPluginManager().callEvent(join);
                            break;
                        case PART:
                            nick = line.substring(1).split("!")[0];
                            manager.setOnline(nick, false);
                            IRCQuitEvent quit = new IRCQuitEvent(NICK.matcher(manager.PLAYER_QUIT_IRC).replaceAll(nick));
                            manager.getPlugin().getServer().getPluginManager().callEvent(quit);
                            break;
                        case PRIVMSG:
                            nick = line.substring(1).split("!")[0];
                            message = line.substring(line.substring(1).indexOf(":", 1)+2);
                            if (message.startsWith(".say")) {
                                target = channel;
                                message = message.substring(5);
                            } else if (message.startsWith(".msg")) {
                                target = message.split(" ")[1];
                                message = message.substring(message.indexOf(" ", message.indexOf(target)));
                            }
                            if (target != null) {
                                IRCMessageEvent msg = new IRCMessageEvent(nick, manager.IRC_TAG, message);
                                manager.getPlugin().getServer().getPluginManager().callEvent(msg);
                            }
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                master.stop();
            }
        }

        public void kill() {
            readIRC();
            interrupt();
        }

    }

    private class IRCWriter extends Thread {
        private Connection master;

        public IRCWriter(Connection conn, String name) {
            super(name);
            master = conn;
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    writeIRC();
                    sleep(200);
                } catch (InterruptedException e) {
                    throw new ThreadCleanupException("IRCWriter cleaned up successfully!");
                }
            }
        }

        public void writeIRC() {
            try {
                for (String line : new ArrayList<String>(lines)) {
                    bWriter.write(line);
                }
                lines.clear();
                bWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
                master.stop();
            }
        }

        public void kill() {
            writeIRC();
            interrupt();
        }
    }

    public Connection(IRCBot bot, String net, String host, int p, String chan) throws IOException {
        manager     = bot;
        connection  = new Socket(host, p);
        bReader     = new BufferedReader(new InputStreamReader( connection.getInputStream()));
        bWriter     = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));

        network     = net;
        hostname    = host;
        port        = p;
        channel     = chan;
        key         = "";

        reader      = new IRCReader(this, "IRCReader: " + host);
        writer      = new IRCWriter(this, "IRCWriter: " + host);

        writeLine("NICK " + manager.NICKNAME);
        writeLine("USER " + manager.NICKNAME + " net.krinsoft.chatsuite " + manager.IDENTITY + " :" + manager.REALNAME);

        writer.start();
        reader.start();
    }

    public Connection(IRCBot bot, String net, String host, int port, String channel, String k) throws IOException {
        this(bot, net, host, port, channel);
        key         = k;
    }

    public String getName() {
        return network;
    }

    public String getInfo() {
        return hostname + ":" + port;
    }

    public void stop() {
        writeLine("QUIT :Connection closing...");
        reader.kill();
        writer.kill();
        try {
            bWriter.flush();
        } catch (IOException e) {
            manager.getPlugin().warn("An error occurred while flushing the output stream for '" + hostname + ":" + port + "'");
        } finally {
            try {
                bWriter.close();
                bReader.close();
            } catch (IOException e) {
                manager.getPlugin().warn("An error occured while closing the streams for '" + hostname + ":" + port + "'");
            } finally {
                try {
                    connection.close();
                } catch (IOException e) {
                    manager.getPlugin().warn("An error occurred while closing the socket for '" + hostname + ":" + port + "'");
                }
            }
        }
    }

    public void writeLine(String message) {
        lines.add(message + "\r\n");
    }

    public void chanMsg(String chan, String message) {
        if (chan == null) { chan = channel; }
        writeLine("PRIVMSG " + chan + " :" + message);
    }

    private void auth() {
        for (String auth : manager.getConfig().getString("networks." + network + ".auth").split("\n")) {
            writeLine(auth);
        }
    }

}
