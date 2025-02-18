package socs.network.node;

import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
	  
	  public void sendMsg(int port, SOSPFPacket msg) {
		  Socket clientSocket = null;
		  ObjectOutputStream out = null;
		  ObjectInputStream in = null;
			
		  try {
			  clientSocket = new Socket("127.0.0.1", port);
			  out = new ObjectOutputStream(clientSocket.getOutputStream());
			  in = new ObjectInputStream(clientSocket.getInputStream());
			  out.writeObject(msg);
			  out.flush();
			  Thread.sleep(1000);
		  } catch (UnknownHostException e) {
			  e.printStackTrace();
		  } catch (IOException e) {
			e.printStackTrace();
		  } catch (InterruptedException e) {
			e.printStackTrace();
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
	  ObjectOutputStream out = null;
	  ObjectInputStream in = null;
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
		in = new ObjectInputStream(clientSocket.getInputStream());
		System.out.println("Sent message");
		
		SOSPFPacket msg = new SOSPFPacket();
		msg.dstIP = simulatedIP;
		msg.sospfType = 2;
		msg.srcIP = this.rd.simulatedIPAddress;
		msg.srcProcessIP = this.rd.processIPAddress;
		msg.srcProcessPort = homePort;
		
		out.writeObject(msg);
		out.flush();

		String res = (String) in.readUTF(); //we wait for the response
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
	  
	  ObjectInputStream in = null;
	  ObjectOutputStream out = null;
	  try {
		  in = new ObjectInputStream(clientSocket.getInputStream());
		  out = new ObjectOutputStream(clientSocket.getOutputStream());
	      
	      SOSPFPacket msg = null;
			try {
				msg = (SOSPFPacket) in.readObject();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
	      if (msg != null) {
		      //parse the message
		      synchronized(ports) {
		    	  if(ports[port-portPrefix] != null) {

		    		  if(msg.sospfType == 0 && msg.srcIP.equals(ports[port-portPrefix].router2.simulatedIPAddress)) {
		    			  //update router descriptions in link
		    			  int idx = port-portPrefix;
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
		    						  send_msg.srcProcessPort = ports[idx].router1.processPortNumber;
		    						  //send msg
		    						  portListeners[port-portPrefix].sendMsg(msg.srcProcessPort + getPortPrefix(msg.srcIP), send_msg);
		    					  }
		    					  else {
		    						  r2.status = RouterStatus.INIT;
		    						  
		    						  SOSPFPacket send_msg = new SOSPFPacket();
		    						  send_msg.dstIP = ports[idx].router2.simulatedIPAddress;
		    						  send_msg.sospfType = 0;
		    						  send_msg.srcIP = rd.simulatedIPAddress;
		    						  send_msg.srcProcessIP = rd.processIPAddress;
		    						  send_msg.srcProcessPort = ports[idx].router1.processPortNumber;
		    						  //send msg
		    						  portListeners[port-portPrefix].sendMsg(msg.srcProcessPort + getPortPrefix(msg.srcIP), send_msg);
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
		    		  else {
		    			  serviceThread.set(true);//turn off the terminal
		    			  attachRequest(port,out,msg);
		    			  serviceThread.set(false);
		    			  startTerminal();
		    		  }
		    	  }
		    	  else if (msg.sospfType == 2) { //attach request
		    		  serviceThread.set(true);//turn off the terminal
	    			  attachRequest(port,out,msg);
	    			  serviceThread.set(false);
	    			  startTerminal();
			      } else {
			    	  out.writeChars("Invalid request");
			    	  out.flush();
			      }
		      }
	      }
	      
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

  private void attachRequest(int port, ObjectOutputStream out, SOSPFPacket msg) {
	  try {
		  System.out.println("\nreceived HELLO from " + msg.srcIP);
    	  if (ports[port-portPrefix] != null) {

    		  System.out.println("Requested port is occupied, the connection shall not be established");

    		  out.writeUTF("Requested port is occupied, the connection shall not be established");
    		  out.flush();

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
    		  out.writeUTF("y"); //send response to client
    		  out.flush();
    		  //add the link on the server side
    		  RouterDescription remote = new RouterDescription();
    		  remote.processPortNumber = msg.srcProcessPort;
    		  remote.processIPAddress = msg.srcProcessIP;
    		  remote.simulatedIPAddress = msg.srcIP;
    		  RouterDescription local = new RouterDescription();
    		  local.processPortNumber = (short) (port - portPrefix);
    		  local.processIPAddress = rd.processIPAddress;
    		  local.simulatedIPAddress = rd.simulatedIPAddress;
    		  Link link = new Link(local, remote);
    		  ports[port-portPrefix]= link;
    	  } else {
    		  System.out.println("You rejected the attach request;");
    		  out.writeUTF("n");
    		  out.flush();
    	  } 
	  }catch(IOException e) {
		  
	  }
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
			  msg.srcProcessPort = ports[idx].router1.processPortNumber;
			  
			  portListeners[idx].sendMsg(ports[idx].router2.processPortNumber + getPortPrefix(msg.dstIP),msg);
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

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
	  System.out.println("Neighbors:");
	  for(Link link : ports) {
		  if(link != null) {
			  System.out.println("\t"+link.router2.simulatedIPAddress + " - status: "+link.router2.status);
		  	  System.out.println("\tPort number: "+link.router2.processPortNumber);
		  }
			  
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
  
  private void description() {
		System.out.println("simulated Ip: "+rd.simulatedIPAddress);
		System.out.println("Process Ip: "+rd.processIPAddress);
		System.out.println("Process port #: "+rd.processPortNumber);
		System.out.println("Status: "+rd.status);
	}
  
  public void terminal() {
    try {
      System.out.print(">> ");
      while (!serviceThread.get()) {
    	  String command = consoleInputQueue.peek();//check for input
    	  if (command != null) {
    		  consoleInputQueue.poll();//remove input from buffer
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
		    }
		    System.out.print(">> ");
		  }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}