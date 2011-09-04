package de.hpi.bpt.process.petri.unf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.hpi.bpt.process.petri.Flow;
import de.hpi.bpt.process.petri.Node;
import de.hpi.bpt.process.petri.PetriNet;
import de.hpi.bpt.process.petri.Place;
import de.hpi.bpt.process.petri.Transition;

/**
 * Occurrence net
 * 
 * @author Artem Polyvyanyy
 */
public class OccurrenceNet extends PetriNet {	
	private Unfolding unf = null;
	
	private Map<Transition,Event> t2e = new HashMap<Transition,Event>();
	private Map<Place,Condition> p2c = new HashMap<Place,Condition>();
	private Map<Event,Transition> e2t = new HashMap<Event,Transition>();
	private Map<Condition,Place> c2p = new HashMap<Condition,Place>();
	
	protected OccurrenceNet(Unfolding unf) {
		this.unf = unf;
		construct();
	}
	
	private void construct() {
		for (Event e : this.unf.getEvents()) {
			Transition t = new Transition(e.getName());
			this.addVertex(t); // TODO
			e2t.put(e,t);
			t2e.put(t,e);
		}
			
		for (Condition c : this.unf.getConditions()) {
			Place p = new Place(c.getName());
			this.addVertex(p); // TODO
			c2p.put(c,p);
			p2c.put(p,c);
		}
		
		for (Event e : this.unf.getEvents()) {
			for (Condition c : e.getPreConditions()) {
				this.addFlow(c2p.get(c), e2t.get(e));
			}
		}
		
		for (Condition c : this.unf.getConditions()) {
			this.addFlow(e2t.get(c.getPreEvent()),c2p.get(c));
		}	
	}
	
	public Unfolding getUnfolding() {
		return this.unf;
	}
	
	public Event getEvent(Transition t) {
		return this.t2e.get(t);
	}
	
	public Condition getCondition(Place p) {
		return this.p2c.get(p);
	}
	
	private BPNode getUnfNode(Node n) {
		if (n instanceof Place)
			return this.getCondition((Place) n);
		
		if (n instanceof Transition)
			return this.getEvent((Transition) n);
		
		return null;
	}
	
	public OrderingRelation getOrderingRelation(Node n1, Node n2) {
		BPNode bpn1 = this.getUnfNode(n1);
		BPNode bpn2 = this.getUnfNode(n2);
		
		if (bpn1!=null && bpn2!=null) 
			this.unf.getOrderingRelation(bpn1,bpn2);
		
		return OrderingRelation.NONE;
	}
	
	public Set<Transition> getCutoffEvents() {
		Set<Transition> result = new HashSet<Transition>();
		for (Event e :this.unf.getCutoffEvents()) result.add(this.e2t.get(e));
		return result;
	}
	
	public Transition getCorrespondingEvent(Transition t) {
		return e2t.get(this.unf.getCorrespondingEvent(t2e.get(t)));
	}
	
	public boolean isCutoffEvent(Transition t) {
		return this.unf.isCutoffEvent(t2e.get(t));
	}

	@Override
	public String toDOT() {
		String result = "digraph G {\n";
		result += "graph [fontname=\"Helvetica\" fontsize=10 nodesep=0.35 ranksep=\"0.25 equally\"];\n";
		result += "node [fontname=\"Helvetica\" fontsize=10 fixedsize style=filled penwidth=\"2\"];\n";
		result += "edge [fontname=\"Helvetica\" fontsize=10 arrowhead=normal color=black];\n";
		result += "\n";
		result += "node [shape=circle];\n";
		
		for (Place n : this.getPlaces())
			result += String.format("\tn%s[label=\"%s\" width=\".3\" height=\".3\" fillcolor=white];\n", n.getId().replace("-", ""), n.getName());
		
		result += "\n";
		result += "node [shape=box];\n";
		
		for (Transition t : this.getTransitions()) {
			if (this.isCutoffEvent(t)) {
				if (t.getName()=="") result += String.format("\tn%s[label=\"%s\" width=\".3\" height=\".1\" fillcolor=orange];\n", t.getId().replace("-", ""), t.getName());
				else result += String.format("\tn%s[label=\"%s\" width=\".3\" height=\".3\" fillcolor=orange];\n", t.getId().replace("-", ""), t.getName());	
			}
			else {
				if (t.getName()=="") result += String.format("\tn%s[label=\"%s\" width=\".3\" height=\".1\" fillcolor=white];\n", t.getId().replace("-", ""), t.getName());
				else result += String.format("\tn%s[label=\"%s\" width=\".3\" height=\".3\" fillcolor=white];\n", t.getId().replace("-", ""), t.getName());
			}
		}
		
		result += "\n";
		for (Flow f: this.getFlowRelation()) {
			result += String.format("\tn%s->n%s;\n", f.getSource().getId().replace("-", ""), f.getTarget().getId().replace("-", ""));
		}
		
		result += "\tedge [fontname=\"Helvetica\" fontsize=8 arrowhead=normal color=orange];\n";
		for (Transition t : this.getCutoffEvents()) {
			result += String.format("\tn%s->n%s;\n", t.getId().replace("-", ""), this.getCorrespondingEvent(t).getId().replace("-", ""));
		}
		
		result += "}\n";
		
		return result;
	}
}
