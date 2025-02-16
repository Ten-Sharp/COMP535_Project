package socs.network.node;

import socs.network.message.SOSPFPacket;
//import socs.network.node.Router.ClientThread;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();
  
  //tells if a service thread is using the terminal
  //used to sognal the terminal thread to stop
  AtomicBoolean serviceThread = new AtomicBoolean(false);
  
  short portPrefix;//used to ensure that all serverSockets are on different ports
  private ConcurrentLinkedQueue<String> consoleInputQueue = new ConcurrentLinkedQueue<String>();
  private BufferedReader consoleReader; //so that the we can close it later when we quit
  //assuming that all routers are with 4 ports
  Link[] ports = new Link[4];
  PortListener[] portListeners = new PortListener[4];
  PortManager[] portManagers = new PortManager[4];
  
//  MultiThreadedServer server = new MultiThreadedServer();
//  boolean serverOn = false;

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    lsd = new LinkStateDatabase(rd);
    portPrefix = getPortPrefix(rd.simulatedIPAddress);
    rd.processIPAddress = getProcessIP();
    
    // start a separate thread to read console input
    new Thread(() -> {
        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = consoleReader.readLine()) != null) {
                consoleInputQueue.offer(line); // Add input to the queue
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }).start();
    
    
    
    //we start server Threads for each port
    for(short i=0; i < 4; i++) {
//    	portListeners[i] = new PortListener((short) (portPrefix + i));
//    	portListeners[i].start();
    	portManagers[i] = new PortManager((short) (portPrefix + i),i);
    	portManagers[i].start();
    }
  }
  
  
  public String getProcessIP(){

        try {
//            Process process = Runtime.getRuntime().exec("hostname -I");
//            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//            String ip = reader.readLine();
            String ip = InetAddress.getLocalHost().toString();
            return ip.split("/")[1];
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return null;
  }
  
  
  //gives the portPrefix for the given simulatedIP address
  //used for socket communication since we are using the loopback address
  //we take the bottom 2 bytes of the simulatedIP address, may change this since it wont work
  //for simulatedIP addresses with equal bottom 2 bytes but different top 2 bytes
  public short getPortPrefix(String simulatedIP) {
	  String[] tmp = simulatedIP.split("\\.");
	  if(tmp.length != 4) {
		  //invalid IP
		  return -1;
	  }
	  int ipNum = Integer.parseInt(tmp[2]) * 256 + //2^8
              Integer.parseInt(tmp[3]);
	  if(ipNum < 3000) {//to avoid getting reserved port numbers
		  ipNum += 3000;
	  }
	  return (short) ipNum;
  }
  
  public class PortManager extends Thread{
	  short port;
	  short machine_port;
	  final Object serverSocketLock = new Object();
	  AtomicBoolean port_running = new AtomicBoolean(true);
	  ServerManager server;
	  ClientManager client;
	  BlockingQueue<SOSPFPacket> msg_queue;
	  AtomicBoolean started = new AtomicBoolean(false);
	  
	  public PortManager(short machine_port,short port) {
		  this.machine_port = machine_port;
		  this.port = port;
		  this.msg_queue = new LinkedBlockingQueue<>();
	  }
	  
	  public void run() {
		  server = new ServerManager(this,machine_port);
		  server.start();
		  
		  //Keeps the server manager on until an interrupt is called to end the thread for cleanup
		  while(port_running.get()) {
			  if(server.isInterrupted())
				  port_running.set(false);
			  try {
				Thread.sleep(1000);
			  }
			  catch(InterruptedException e) {
				  e.printStackTrace();
			  }
		  }
	  }
	  
	  //initalizes a client manager using a new client socket to be output for a specific port
	  public void initClient(String HostAddress,int connectedPort) {
		  try {
			Socket client = new Socket(HostAddress,connectedPort);
			this.client = new ClientManager(this,client);
			  this.client.start();
			
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		  
	  }
	  
	  //Initializes a client manager for a specific port using an already existing client socket
	  //The ObjectOutputStream is passed to make sure only one output stream per socket
	  //reuses the client socket from the attach function
	  public void initClient(Socket client,ObjectOutputStream out) {
		  this.client = new ClientManager(this,client,out);
		  this.client.start();
	  }
	  
	  //finds the ports index of a router with a specific simulated IP
	  public int findRouterIndex(String simulatedIP) {
		  System.out.println("Searched IP: "+simulatedIP);
		  synchronized(ports) {
			  for(int i =0;i<ports.length;i++) {
				  if(ports[i]!=null && ports[i].router2.simulatedIPAddress.equals(simulatedIP)) {
					  System.out.println(ports[i].router2.simulatedIPAddress);
					  return i;
				  }
			  }
			  
			  return -1;
		  }
	  } 
	  
	  //Function for processing messages, simple for now just to allow for start to work but can be changes for different types of packets in the future
	  public void processMsg(SOSPFPacket msg) {
		  System.out.println(msg.srcIP);
		  System.out.println(msg.srcProcessIP);
		  System.out.println(msg.dstIP);
		  System.out.println(msg.srcProcessPort);
		  //process and tell what client needs to send
		  if(!this.started.get()) {
			  start_msg(msg);
		  }		  
	  }
	  
	  //Sends a message out from the port by adding a packet to the blocking queue for the client socket to then pick up
	  public void sendMsg(SOSPFPacket msg) {
		  try {
			msg_queue.put(msg);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	  }
	  
	  //Message processing for start function, this is used when start is called but not when attach is called
	  public void start_msg(SOSPFPacket msg) {
		  if(msg.sospfType == 0) {
			  //update router descriptions in link
			  int idx = findRouterIndex(msg.srcIP);
			  synchronized(ports) {
				  RouterDescription r2 = ports[idx].router2;
				  if(r2.status == null) {
					  if(rd.status == RouterStatus.INIT) {
						  r2.status = RouterStatus.TWO_WAY;
						  
						  SOSPFPacket send_msg = new SOSPFPacket();
						  send_msg.dstIP = ports[idx].router2.simulatedIPAddress;
						  send_msg.sospfType = 0;
						  send_msg.srcIP = rd.simulatedIPAddress;
						  send_msg.srcProcessIP = rd.processIPAddress;
						  send_msg.srcProcessPort = rd.processPortNumber;
						  //send msg
						  this.started.set(true);
						  
						  try {
							  msg_queue.put(send_msg);
							  
						  }
						  catch(InterruptedException e) {
							  e.printStackTrace();
						  }
					  }
					  else {
						  r2.status = RouterStatus.INIT;
						  
						  SOSPFPacket send_msg = new SOSPFPacket();
						  send_msg.dstIP = ports[idx].router2.simulatedIPAddress;
						  send_msg.sospfType = 0;
						  send_msg.srcIP = rd.simulatedIPAddress;
						  send_msg.srcProcessIP = rd.processIPAddress;
						  send_msg.srcProcessPort = rd.processPortNumber;
						  //send msg
						  try {
							  msg_queue.put(send_msg);
							  
						  }
						  catch(InterruptedException e) {
							  e.printStackTrace();
						  }
					  }
				  }
				  else if(r2.status == RouterStatus.INIT){
					  r2.status = RouterStatus.TWO_WAY;		  
				  }
			  }
			  
		  }
		  else if(msg.sospfType == 1) {
			  //link state update
		  }
			  
		  
	  }
	  	  
  }
  
  //server manager for server socket (input) for a specific port
  class ServerManager extends Thread{
	  PortManager manager;
	  Socket client;
	  ServerSocket server;
	  short port;
	  AtomicBoolean serverOn = new AtomicBoolean(true);
	  AtomicBoolean clientConnected = new AtomicBoolean(false);
	  ObjectInputStream in = null;
	  
	  public ServerManager(PortManager manager,short port) {
		  this.manager = manager;
		  this.port = port;
	  }
	  
	  public void run() {
		  try {
			  server = new ServerSocket(port);
		  }
		  catch(IOException e) {
			  e.printStackTrace();
		  }
		  
		  while(serverOn.get()) {
			  //stops nd closes server socket when thread interrupted, used for clean up later from port manager
			  if(Thread.currentThread().isInterrupted()) {
				  break;
			  }
			  try {
				  client = server.accept();
				  in = new ObjectInputStream(client.getInputStream());
				  
				  while(!client.isClosed()) {
					  Object msg1 = in.readObject();
					  SOSPFPacket msg = (SOSPFPacket) msg1;
					  
					  //if a client is not yet connected send to request handler for attach functionality
					  //if a client is succesfully attached, it skips this and sends messages to processMsg in port manager
					  if(!clientConnected.get()) {
						  boolean result = requestHandler(client,msg.srcProcessPort,server,msg);
						  if(result) {
//								  Socket send_client = new Socket(msg.srcProcessIP,(getPortPrefix(msg.srcIP)+msg.srcProcessPort));
							  manager.initClient(rd.processIPAddress,(getPortPrefix(msg.srcIP)+msg.srcProcessPort));
							  clientConnected.set(true);
						  }
					  }
					  else {
						  manager.processMsg(msg);
					  }
				  }
			  }
			  catch(IOException e) {
				  e.printStackTrace();
			  } catch (ClassNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		  }
		  try {
			server.close();
			in.close();
//			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	  }
	  
  }
  
  //client socket manager for each port
  class ClientManager extends Thread{
	  PortManager manager;
	  short sendPort;
	  Socket client;
	  ObjectOutputStream out = null;
//	  BufferedReader in = null;
	  
	  //overloaded constructors to allow for it to be initialized with a socket that has an existing out stream
	  public ClientManager(PortManager manager,Socket client) {
		  
		  this.manager = manager;
		  this.client = client;
		  try {
				this.out = new ObjectOutputStream(client.getOutputStream());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	  }
	  
	  public ClientManager(PortManager manager, Socket client, ObjectOutputStream out) {
		  this.manager = manager;
		  this.client = client;
		  this.out = out;
	  }
	  
	  public void run() {
		  try {

			  //While thread is not interrupted it waits for packets to appear on the Blocking queue and sends them when one appears
			  while(!Thread.currentThread().isInterrupted()) {
				  
				  SOSPFPacket msg = manager.msg_queue.take();

				  out.writeObject(msg);
				  out.flush();
			  }
		  }
		  catch(IOException e) {
			  e.printStackTrace();
		  } 
		  catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		try {
			client.close();
			out.close();
//			in.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	  }
  }
  
  
  //used to have threads listening to each of the 4 ports
  //of the router instance
  public class PortListener extends Thread{
	  ServerSocket serverSocket;
	  short port;
	  AtomicBoolean serverOn = new AtomicBoolean(true);
	  final Object serverSocketLock = new Object();
	  
	  public PortListener(short port) {
		  this.port = port;
	  }
	  
	  @Override
	  public void run() {
		  try {
			  synchronized (serverSocketLock){
				  serverSocket = new ServerSocket(port);
			  }
		  } catch (IOException e) {
			  System.out.println("Could not create server socket on port " + Short.toString(port) + " Quitting.");
			  e.printStackTrace();
			  System.exit(-1); 
		  }
		  while (serverOn.get()) {
			  try {
				Socket clientSocket = serverSocket.accept();
				//code bellow called after we get a connection request
				//since accept blocks until a client connect to port
				if(serverOn.get()) {//we have to check again
					ClientServiceThread service = new ClientServiceThread(clientSocket,port,serverSocket);
					service.start();
				}
			  } catch (IOException e) {
				if (serverOn.get()) {
					//abnormal behavior if accept is closed while portListener
					//is still supposed to be listening
					System.out.println("Exception encountered on accept");
					e.printStackTrace();
				}
			  }
		  }
		  
		  synchronized(serverSocketLock) {
			  try {
				  if (serverSocket != null) {
					  serverSocket.close();
				  }
			  } catch (IOException e){
				  e.printStackTrace();
			  }
		  }
	  }
	  
	  
	  //used to close serverSockets
	  public void closeServerSocket() {
		  serverOn.set(false);
		  synchronized(serverSocketLock) {
			  try {
				  if (serverSocket != null) {
					  serverSocket.close();
				  }
			  } catch (IOException e){
				  e.printStackTrace();
			  }
		  }
	  }
  }
  
  
  //enables us to handle concurrent requests on the same port
  //for the same router instance, we handle the request with
  //request handler
  public class ClientServiceThread extends Thread {
	  Socket clientSocket;
	  short port;
	  ServerSocket serverSocket;
	  
	  public ClientServiceThread(Socket clientSocket, short port, ServerSocket serverSocket) {
		  this.clientSocket = clientSocket;
		  this.port = port;
		  this.serverSocket = serverSocket;
	  }
	  
	  @Override
	  public void run() {
//		  requestHandler(clientSocket, port, serverSocket);
	  }
  }
  
  
  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {

  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  private void processDisconnect(short portNumber) {

  }
  
  //returns the first free port
  private synchronized short getFreePort() {
	  for(short i = 0;i < ports.length; i++) {
		  if (ports[i] == null) {
			  return i;
		  }
	  }
	  //indicates no free port
	  return -1;
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */
  private synchronized void processAttach(String processIP, short processPort, String simulatedIP) {
	  
	  //prevent connecting to self
	  if (simulatedIP.equals(rd.simulatedIPAddress)) {
		  System.out.println("Cannot attach to self");
		  return;
	  }
	  
	  // find an available port on our current router
	  short homePort = getFreePort();
	  if (homePort == -1) {
		  //means there is no free port
		  System.out.println("Ports are all occupied, the connection shall not be established");
		  return;
	  }
	  Socket clientSocket = null;
	  ObjectOutputStream out = null;
	  BufferedReader in = null;


		short pre = getPortPrefix(simulatedIP);
		if(pre == -1) {
			System.out.println("IP address must be in decimal IPv4 format: xxx.xxx.xxx.xxx");
			return;
		}
	  try {
		//we use the loopback address, to find the actual router, we use a mapping
		//simulatedIP -> unique port on loopback address
		clientSocket = new Socket("127.0.0.1", pre + processPort);
		out = new ObjectOutputStream(clientSocket.getOutputStream());
		in = new BufferedReader (new InputStreamReader(clientSocket.getInputStream()));
//		in = new ObjectInputStream(clientSocket.getInputStream());
		
		//Now sends SOSPFPackets instead of HELLO string msg, works the same though
		SOSPFPacket msg = new SOSPFPacket();
		msg.srcProcessIP = rd.processIPAddress;
		msg.dstIP = simulatedIP;
		msg.sospfType = 0;
		msg.srcProcessPort = processPort;
		msg.srcIP = rd.simulatedIPAddress;
		
		System.out.println("Sent message: "+simulatedIP);
		//we send the conection request, which also encodes the info about the client
//		out.println("HELLOX"+processIP+"X"+Short.toString(homePort)+"X"+simulatedIP);
		out.writeObject(msg);
		out.flush();
		

		String res = in.readLine(); //we wait for the response
//		String res = (String) in.readObject();
		
		if (res != null) {
			if (res.equalsIgnoreCase("y")) {
		        // Connection accepted, complete the link setup.
				RouterDescription remote = new RouterDescription();
				remote.processIPAddress = processIP;
				remote.processPortNumber = processPort;
				remote.simulatedIPAddress = simulatedIP;
				RouterDescription local = new RouterDescription();
				local.processIPAddress = processIP;
				local.processPortNumber = homePort;
				local.simulatedIPAddress = rd.simulatedIPAddress;
		        Link link = new Link(local, remote);
		        ports[homePort] = link;
		        //if connection is accepted initialized the client manager with the existing socket
		        portManagers[homePort].initClient(clientSocket,out);
		        portManagers[homePort].server.clientConnected.set(true);
		        System.out.println("Connection established with " + simulatedIP);
		    } else {
		        System.out.println("Your attach request has been \nrejected;");
		        clientSocket.close();
		    }
		}
	  } catch (IOException e) {
		System.out.println("Connection Error\nMake sure other router is live or that the IP address is valid");
		try {
			clientSocket.close();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		//e.printStackTrace();
	  } finally {
//		  try {
//			  if (clientSocket != null) {
//					clientSocket.close();
//				}
//				if (out != null) {
//					out.close();
//				}
//				if (in != null) {
//					in.close();
//				}
//		  }catch(IOException e) {
//			  e.printStackTrace();
//		  }
			
//			out = null;
//			in = null;
	  }
  }

  /**
   * process request from the remote router. 
   * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request. 
   * The intuition is that if router2 is an unknown/anomaly router, it is always safe to reject the attached request from router2.
   */
  private boolean requestHandler(Socket clientSocket, int port, ServerSocket serverSocket, SOSPFPacket msg) {
//	  serviceThread.set(true);//turn off the terminal
	  ObjectInputStream in = null;
	  PrintWriter out = null;
//	  ObjectOutputStream out = null;

	  try {
//	      in = new ObjectInputStream(clientSocket.getInputStream());
	      out = new PrintWriter(clientSocket.getOutputStream(), true); 
//		  out = new ObjectOutputStream(clientSocket.getOutputStream());
		  
	      if(msg == null)
	    	  System.out.println("MSG IS NULL");
//	      SOSPFPacket msg = (SOSPFPacket) in.readObject();
	      if (msg != null) {
		      //parse the message
//		      String[] clientInfo = msg.split("X");
//		      msg = clientInfo[0];
	    	  port = getPortPrefix(msg.dstIP) + port;
		      //for now we assume that request handler is only used for the attach command
		      //IF NOT we must change the above logic a bit
		      synchronized(ports) {
			      if(msg.sospfType == 0) {
			    	  System.out.println("\nreceived HELLO from " + msg.srcIP);
			    	  if (ports[port-portPrefix] != null) {
			    		  //means that the request port is unavailable
			    		  //inform server
			    		  System.out.println("Requested port is occupied, the connection shall not be established");
			    		  //inform client
			    		  out.println("Requested port is occupied, the connection shall not be established");
			    		  serviceThread.set(false);
			    		  startTerminal();
			    		  return false;
			    	  }
			    	  System.out.print("Do you accept this request? (Y/N) \n");
			    	  
                      String answer = null;
                      while (answer == null) { // loop until input is available
                          answer = consoleInputQueue.peek(); // check the queue
                      }
                      consoleInputQueue.poll(); //remove from queue
			    	  
			    	  if (answer.equalsIgnoreCase("y")) {
			    		  System.out.println("You accepted the attach request;");
			    		  out.println("y"); //send response to client
			    		  //add the link on the server side
			    		  RouterDescription remote = new RouterDescription();
//			    		  remote.processPortNumber = Short.parseShort(clientInfo[2]);
			    		  remote.processPortNumber = msg.srcProcessPort;
//			    		  remote.processIPAddress = clientInfo[1];
//			    		  remote.simulatedIPAddress = clientInfo[3];
			    		  remote.processIPAddress = msg.srcProcessIP;
			    		  remote.simulatedIPAddress = msg.srcIP;
			    		  RouterDescription local = new RouterDescription();
			    		  local.processPortNumber = (short) (port - portPrefix);
//			    		  local.processIPAddress = clientInfo[1];
			    		  local.processIPAddress = rd.processIPAddress;
			    		  local.simulatedIPAddress = rd.simulatedIPAddress;
			    		  Link link = new Link(local, remote);
			    		  ports[port-portPrefix]= link;
			    		  
			    		  return true;
			    	  } else {
			    		  System.out.println("You rejected the attach request;");
			    		  out.println("n"); //send response to client
			    		  return false;
			    	  } 
			      } else {
			    	  out.println("Invalid request");
			    	  return false;
			      }
		      }
	      }
	      serviceThread.set(false);
		  startTerminal();
	  } catch (IOException e) {
		e.printStackTrace();
	  } finally {
//	      try {
//	    	if (in != null) {
//	    		in.close();
//	    	}
//	    	if (in != null) {
//	    		out.close();
//	    	}
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	  }
	  
	  return false;
  }


/**
   * broadcast Hello to neighbors
   */
  private void processStart() {
	  rd.status = RouterStatus.INIT;
	  for(int idx = 0;idx<ports.length;idx++) {
		  if(ports[idx] != null) {
			  SOSPFPacket msg = new SOSPFPacket();
			  msg.dstIP = ports[idx].router2.simulatedIPAddress;
			  msg.sospfType = 0;
			  msg.srcIP = this.rd.simulatedIPAddress;
			  msg.srcProcessIP = this.rd.processIPAddress;
			  msg.srcProcessPort = this.rd.processPortNumber;
			  
			  portManagers[idx].sendMsg(msg);
		  }
	  }
	  
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort,
                              String simulatedIP) {

  }
  
  private void description() {
		System.out.println("simulated Ip: "+rd.simulatedIPAddress);
		System.out.println("Process Ip: "+rd.processIPAddress);
		System.out.println("Process port #: "+rd.processPortNumber);
		System.out.println("Status: "+rd.status);
	}

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
	  System.out.println("Neighbors:");
	  for(Link link : ports) {
		  if(link != null)
			  System.out.println("\t"+link.router2.simulatedIPAddress + " - status: "+link.router2.status);
	  }
  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {
	  //TODO
	  //needs to trigger LSD Synchronization and Multicast quit packets
	  
	  serviceThread.set(true);//turn off the terminal
	  
      if (consoleReader != null){
          try {
              consoleReader.close();
          } catch (IOException e) {
              e.printStackTrace();
          }
      }
      
	  //Turn off all 4 portListeners
	  for(PortListener portListener: portListeners) {
		  portListener.closeServerSocket();
		  try {
			portListener.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	  }
	  //temporary successful exit for testing purposes
	  System.out.println("Bye!");
	  System.exit(0);
  }
  
  //to start the terminal
  public void startTerminal() {
  //start a spearate thread for the terminal
    new Thread(() -> {
    	terminal();
    }).start();
  }
  
  public void terminal() {
    try {
      System.out.print(">> ");
      while (!serviceThread.get()) {
    	  String command = consoleInputQueue.peek();
    	  if (command != null) {
    		  consoleInputQueue.poll();//remove head
		    if (command.startsWith("detect ")) {
		      String[] cmdLine = command.split(" ");
		      processDetect(cmdLine[1]);
		    } else if (command.startsWith("disconnect ")) {
		      String[] cmdLine = command.split(" ");
		      processDisconnect(Short.parseShort(cmdLine[1]));
		    } else if (command.startsWith("quit")) {
		      processQuit();
		    } else if (command.startsWith("attach ")) {
		      String[] cmdLine = command.split(" ");
		      processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
		              cmdLine[3] );
		    } else if (command.equals("start")) {
		      processStart();
		    } else if (command.equals("connect ")) {
		      String[] cmdLine = command.split(" ");
		      processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
		              cmdLine[3]);
		    } else if (command.equals("neighbors")) {
		      //output neighbors
		      processNeighbors();
		    } else if (command.equals("description")) {
		    	description();
		    } else {
		      //invalid command
		      System.out.println("Invalid Command");
		      //break;
		    }
		    System.out.print(">> ");
		  }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}