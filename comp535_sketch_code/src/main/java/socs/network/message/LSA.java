package socs.network.message;

import java.io.Serializable;
import java.util.LinkedList;

public class LSA implements Serializable {

  private static final long serialVersionUID = 1L;
  //IP address of the router originate this LSA
  public String linkStateID;
  public boolean deleted; //tells us if lsa is deleted
  public int lsaSeqNumber = Integer.MIN_VALUE;

  public LinkedList<LinkDescription> links = new LinkedList<LinkDescription>();

  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append(linkStateID + ":").append(lsaSeqNumber + "\n");
    for (LinkDescription ld : links) {
      sb.append(ld);
    }
    sb.append("\n");
    return sb.toString();
  }
  
  @Override
  public boolean equals(Object o) {	  
	  
	  if (o == this)return true;

	  if(!(o instanceof LSA)) return false;
	  
	  LSA lsa = (LSA) o;
	  
	  return (linkStateID.equals(lsa.linkStateID));
  }
}
