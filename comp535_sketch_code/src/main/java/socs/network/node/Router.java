package socs.network.node;

import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
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

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    lsd = new LinkStateDatabase(rd);
    portPrefix = getPortPrefix(rd.simulatedIPAddress);
    
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
    	portListeners[i] = new PortListener((short) (portPrefix + i));
    	portListeners[i].start();
    }
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
		  requestHandler(clientSocket, port, serverSocket);
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
	  PrintWriter out = null;
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
		out = new PrintWriter(clientSocket.getOutputStream(), true);
		in = new BufferedReader (new InputStreamReader(clientSocket.getInputStream()));
		System.out.println("Sent message");
		//we send the conection request, which also encodes the info about the client
		out.println("HELLOX"+processIP+"X"+Short.toString(homePort)+"X"+simulatedIP);
		String res = in.readLine(); //we wait for the response
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
		        System.out.println("Connection established with " + simulatedIP);
		    } else {
		        System.out.println("Your attach request has been \nrejected;");
		    }
		}
	  } catch (IOException e) {
		System.out.println("Connection Error\nMake sure other router is live or that the IP address is valid");
		//e.printStackTrace();
	  } finally {
		//close our ressources
		try {
			if (clientSocket != null) {
				clientSocket.close();
			}
			if (out != null) {
				out.close();
			}
			if (in != null) {
				in.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	  }
  }

  /**
   * process request from the remote router. 
   * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request. 
   * The intuition is that if router2 is an unknown/anomaly router, it is always safe to reject the attached request from router2.
   */
  private void requestHandler(Socket clientSocket, short port, ServerSocket serverSocket) {
	  serviceThread.set(true);//turn off the terminal
	  BufferedReader in = null;
	  PrintWriter out = null;
	  try {
	      in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
	      out = new PrintWriter(clientSocket.getOutputStream(), true); 
	      
	      String msg = in.readLine();
	      if (msg != null) {
		      //parse the message
		      String[] clientInfo = msg.split("X");
		      msg = clientInfo[0];
		      //for now we assume that request handler is only used for the attach command
		      //IF NOT we must change the above logic a bit
		      synchronized(ports) {
			      if(msg.equals("HELLO")) {
			    	  System.out.println("\nreceived HELLO from " + clientInfo[1]);
			    	  if (ports[port-portPrefix] != null) {
			    		  //means that the request port is unavailable
			    		  //inform server
			    		  System.out.println("Requested port is occupied, the connection shall not be established");
			    		  //inform client
			    		  out.println("Requested port is occupied, the connection shall not be established");
			    		  serviceThread.set(false);
			    		  startTerminal();
			    		  return;
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
			    		  out.flush();
			    		  //add the link on the server side
			    		  RouterDescription remote = new RouterDescription();
			    		  remote.processPortNumber = Short.parseShort(clientInfo[2]);
			    		  remote.processIPAddress = clientInfo[1];
			    		  remote.simulatedIPAddress = clientInfo[3];
			    		  RouterDescription local = new RouterDescription();
			    		  local.processPortNumber = (short) (port - portPrefix);
			    		  local.processIPAddress = clientInfo[1];
			    		  local.simulatedIPAddress = rd.simulatedIPAddress;
			    		  Link link = new Link(local, remote);
			    		  ports[port-portPrefix]= link;
			    	  } else {
			    		  System.out.println("You rejected the attach request;");
			    		  out.println("n"); //send response to client
			    		  out.flush();
			    	  } 
			      } else {
			    	  out.println("Invalid request");
			      }
		      }
	      }
	      serviceThread.set(false);
		  startTerminal();
	  } catch (IOException e) {
		e.printStackTrace();
	  } finally {
	      try {
	    	if (in != null) {
	    		in.close();
	    	}
	    	if (in != null) {
	    		out.close();
	    	}
		} catch (IOException e) {
			e.printStackTrace();
		}
	  }
  }

/**
   * broadcast Hello to neighbors
   */
  private void processStart() {

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

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {

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