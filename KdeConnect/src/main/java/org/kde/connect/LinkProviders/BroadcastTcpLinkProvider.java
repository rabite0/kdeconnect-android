package org.kde.connect.LinkProviders;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import org.apache.mina.core.future.ReadFuture;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.textline.LineDelimiter;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.kde.connect.ComputerLinks.BaseComputerLink;
import org.kde.connect.ComputerLinks.NioSessionComputerLink;
import org.kde.connect.ComputerLinks.TcpComputerLink;
import org.kde.connect.NetworkPackage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashMap;

public class BroadcastTcpLinkProvider extends BaseLinkProvider {

    private final static int port = 1714;

    Context context;
    HashMap<String, BaseComputerLink> visibleComputers = new HashMap<String, BaseComputerLink>();
    HashMap<String, NioSessionComputerLink> nioSessions = new HashMap<String, NioSessionComputerLink>();
    private boolean started = false;

    NioSocketAcceptor tcpAcceptor = null;
    NioDatagramAcceptor updAcceptor = null;

    private void addLink(NetworkPackage identityPackage, BaseComputerLink link) {
        Log.e("BroadcastTcpLinkProvider","addLink to "+identityPackage.getString("deviceName"));
        String deviceId = identityPackage.getString("deviceId");
        BaseComputerLink oldLink = visibleComputers.get(deviceId);
        if (oldLink != null) {
            Log.e("BroadcastTcpLinkProvider","Removing old connection to same device");
            connectionLost(oldLink);
        }
        visibleComputers.put(deviceId, link);
        connectionAccepted(identityPackage, link);
    }

    public BroadcastTcpLinkProvider(Context context) {

        this.context = context;

        //This handles the case when I'm the new device in the network and somebody answers my introduction package
        tcpAcceptor = new NioSocketAcceptor();
        tcpAcceptor.setHandler(new IoHandlerAdapter() {
            @Override
            public void sessionClosed(IoSession session) throws Exception {

                String address = ((InetSocketAddress) session.getRemoteAddress()).toString();
                NioSessionComputerLink brokenLink = nioSessions.remove(address);
                if (brokenLink != null) {
                    connectionLost(brokenLink);
                    String id = brokenLink.getDeviceId();
                    visibleComputers.remove(id);
                }
            }

            @Override
            public void messageReceived(IoSession session, Object message) throws Exception {
                super.messageReceived(session, message);

                String address = ((InetSocketAddress) session.getRemoteAddress()).toString();
                Log.e("BroadcastTcpLinkProvider","Incoming package, address: "+address);

                String theMessage = (String) message;
                NetworkPackage np = NetworkPackage.unserialize(theMessage);

                NioSessionComputerLink prevLink = nioSessions.get(address);

                if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                    NioSessionComputerLink link = new NioSessionComputerLink(session, np.getString("deviceId"), BroadcastTcpLinkProvider.this);
                    nioSessions.put(address,link);
                    addLink(np, link);
                } else {
                    if (prevLink == null) {
                        Log.e("BroadcastTcpLinkProvider","2 Expecting an identity package");
                    } else {
                        prevLink.injectNetworkPackage(np);
                    }
                }

            }
        });
        tcpAcceptor.getSessionConfig().setKeepAlive(true);
        //TextLineCodecFactory will split incoming data delimited by the given string
        tcpAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );
        try {
            tcpAcceptor.getSessionConfig().setReuseAddress(true);
            tcpAcceptor.bind(new InetSocketAddress(port));
        } catch(Exception e) {
            Log.e("BroadcastTcpLinkProvider", "Error: Could not bind tcp socket");
            e.printStackTrace();
        }


    }

    @Override
    public void onStart() {

        //This handles the case when I'm the existing device in the network and receive a "hello" UDP package
        updAcceptor = new NioDatagramAcceptor();
        updAcceptor.getSessionConfig().setReuseAddress(true);        //Share port if existing
        updAcceptor.setHandler(new IoHandlerAdapter(){
            @Override
            public void messageReceived(IoSession session, Object message) throws Exception {
                super.messageReceived(session, message);

                Log.e("BroadcastTcpLinkProvider","Udp message received (" + message.getClass() + ") " + message.toString());

                NetworkPackage np = null;

                try {
                    //We should receive a string thanks to the TextLineCodecFactory filter
                    String theMessage = (String) message;
                    np = NetworkPackage.unserialize(theMessage);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("BroadcastTcpLinkProvider","Could not unserialize package");
                }

                if (np != null) {

                    final NetworkPackage identityPackage = np;
                    if (!np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                        Log.e("BroadcastTcpLinkProvider","1 Expecting an identity package");
                        return;
                    }

                    Log.e("BroadcastTcpLinkProvider","It is an identity package, creating link");

                    final TcpComputerLink link = new TcpComputerLink(np.getString("deviceId"), BroadcastTcpLinkProvider.this);

                    final InetSocketAddress address = (InetSocketAddress) session.getRemoteAddress();

                    //This handler inside a thread with a looper inside a handler is ultra hackish
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Looper l = Looper.myLooper();
                                if (l != null) l.quit();
                                try {
                                    Looper.prepare();
                                } catch(Exception e) {
                                    e.printStackTrace();
                                    Log.e("BroadcastTcpLinkProvider","Looper prepare exception");
                                }
                                final Looper l2 = Looper.myLooper();
                                link.connect(address.getAddress(), port, new Handler() {
                                    @Override
                                    public void handleMessage(Message msg) {

                                        Log.e("BroadcastTcpLinkProvider","Link established, sending own identity");

                                        NetworkPackage np2 = NetworkPackage.createIdentityPackage(context);
                                        link.sendPackage(np2);

                                        addLink(identityPackage, link);

                                        l2.quit();
                                    }
                                }, new Handler() {
                                    @Override
                                    public void handleMessage(Message msg) {
                                        connectionLost(link);
                                        visibleComputers.remove(identityPackage.getString("deviceId"));
                                    }
                                });
                                Looper.loop();
                            } catch (Exception e) {
                                e.printStackTrace();
                                Log.e("BroadcastTcpLinkProvider","Exception");
                                Looper l = Looper.myLooper();
                                if (l != null) l.quit();
                            }

                        }
                    }).run();

                    Log.e("DONE","DONE0");

                }
                Log.e("DONE","DONE2");

            }
        });
        //TextLineCodecFactory will split incoming data delimited by the given string
        updAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );
        try {
            updAcceptor.bind(new InetSocketAddress(port));
        } catch(Exception e) {
            Log.e("BroadcastTcpLinkProvider", "Error: Could not bind udp socket");
            e.printStackTrace();
        }

        onNetworkChange();

    }

    @Override
    public void onNetworkChange() {

        Log.e("BroadcastTcpLinkProvider","OnNetworkChange: " + (tcpAcceptor != null));

        if (tcpAcceptor == null) return;

        //I'm on a new network, let's be polite and introduce myself
        new AsyncTask<Void,Void,Void>() {
            @Override
            protected Void doInBackground(Void... voids) {

                try {
                    String s = NetworkPackage.createIdentityPackage(context).serialize();
                    byte[] b = s.getBytes("UTF-8");
                    DatagramPacket packet = new DatagramPacket(b, b.length, InetAddress.getByAddress(new byte[]{-1,-1,-1,-1}), port);
                    DatagramSocket socket = new DatagramSocket();
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.send(packet);
                    Log.e("BroadcastTcpLinkProvider","Udp identity package sent");
                } catch(Exception e) {
                    e.printStackTrace();
                    Log.e("BroadcastTcpLinkProvider","Sending udp identity package failed");
                }

                return null;
            }

        }.execute();
    }

    @Override
    public void onStop() {

        if (updAcceptor != null) {
            updAcceptor.unbind();
            updAcceptor = null;
        }

    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public String getName() {
        return "BroadcastTcpLinkProvider";
    }
}