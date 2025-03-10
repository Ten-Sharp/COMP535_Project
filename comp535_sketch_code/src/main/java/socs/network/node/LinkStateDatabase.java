package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   */
  String getShortestPath(String destinationIP) {
	  HashSet<String> visited = new HashSet<>();//to keep track of visted routers
	  Queue<LinkedList<String>> queue = new LinkedList<>(); //keeps track of paths
	  
	  LinkedList<String> initialPath = new LinkedList<>();
	  initialPath.add(rd.simulatedIPAddress);
	  queue.add(initialPath);
	  visited.add(rd.simulatedIPAddress);
	  
	  while (!queue.isEmpty()) {
	        LinkedList<String> currentPath = queue.poll();
	        String currentIP = currentPath.getLast();

	        if (currentIP.equals(destinationIP)) {
	            return buildPathString(currentPath);
	        }

	        LSA currentLSA = _store.get(currentIP);
	        if (currentLSA == null || currentLSA.links == null) {
	            continue;
	        }

	        for (LinkDescription link : currentLSA.links) {
	        	if (!visited.contains(link.linkID)) {
	                visited.add(link.linkID);
	                LinkedList<String> newPath = new LinkedList<>(currentPath);
	                newPath.add(link.linkID);
	                queue.add(newPath);
	            }
	        }
	    }
	    return null; // No path found	  
  }
  
  //used to build output string for shortest path
  private String buildPathString(LinkedList<String> path) {
	  StringBuilder result = new StringBuilder();
	  
	  result.append(path.poll());
	  
	  for (int i = 0; i < path.size(); i++) {
		  result.append(" -> ");
		  result.append(path.get(i));
	  }
	  
	  return result.toString();
  }

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append(" (" + lsa.lsaSeqNumber + ")").append(":\n").append("-------------------------------\n");
      for (LinkDescription ld : lsa.links) {
        sb.append("\t\t"+ld.linkID).append(" | ").append(ld.portNum).append("\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}
