package socs.network.message;

import java.io.*;
import java.util.Vector;

public class SOSPFPacket implements Serializable {

  private static final long serialVersionUID = 1L;//not really necessary just to get rid of warning
  
  //for inter-process communication
  public String srcProcessIP;
  public short srcProcessPort;

  //simulated IP address
  public String srcIP;
  public String dstIP;

  //common header
  public short sospfType; //0 - HELLO, 1 - LinkState Update, 2 - attach request, 3 disconnect, 4 quit, 5 Connect
  public String routerID;

  //used by HELLO message to identify the sender of the message
  //e.g. when router A sends HELLO to its neighbor, it has to fill this field with its own
  //simulated IP address
  public String neighborID; //neighbor's simulated IP address

  //used by LSAUPDATE
  public Vector<LSA> lsaArray = null;
}
