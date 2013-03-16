package org.jbpt.petri.unfolding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jbpt.petri.Marking;
import org.jbpt.petri.NetSystem;
import org.jbpt.petri.PetriNet;
import org.jbpt.petri.Place;
import org.jbpt.petri.Transition;


/**
 * (Complete prefix) unfolding of a net system.<br/><br/>
 *
 * This class implements techniques described in:
 * - Javier Esparza, Stefan Roemer, Walter Vogler: An Improvement of McMillan's Unfolding Algorithm. Formal Methods in System Design (FMSD) 20(3):285-310 (2002).
 * - Victor Khomenko: Model Checking Based on Prefixes of Petri Net Unfoldings. PhD Thesis. February (2003).
 * 
 * @author Artem Polyvyanyy
 */
public class Unfolding {
	// originative net system
	protected NetSystem sys = null;
	// unfolding setup
	protected UnfoldingSetup setup = null;

	// events and conditions of unfolding
	protected Set<Event> events		= new HashSet<Event>();			// events of the unfolding
	protected Set<Condition> conds	= new HashSet<Condition>();		// conditions of the unfolding
	
	// indexes for conflict and concurrency relations 
	private Map<BPNode,Set<BPNode>> EX = new HashMap<BPNode,Set<BPNode>>();
	private Map<BPNode,Set<BPNode>> notEX = new HashMap<BPNode,Set<BPNode>>();
	private Map<BPNode,Set<BPNode>> CO = new HashMap<BPNode,Set<BPNode>>();
	private Map<BPNode,Set<BPNode>> notCO = new HashMap<BPNode,Set<BPNode>>();
	
	// map a condition to a set of cuts that contain the condition
	protected Map<Condition,Collection<Cut>> c2cut = new HashMap<Condition,Collection<Cut>>();
	
	// maps of transitions/places to sets of events/conditions (occurrences of transitions/places)
	protected Map<Transition,Set<Event>> t2es	= new HashMap<Transition,Set<Event>>();
	protected Map<Place,Set<Condition>> p2cs	= new HashMap<Place,Set<Condition>>();
	
	// causality: maps node of unfolding to a set of preceding nodes
	protected Map<BPNode,Set<BPNode>> ca = new HashMap<BPNode,Set<BPNode>>();
	// concurrency: maps node of unfolding to a set of concurrent nodes
	protected Map<BPNode,Set<BPNode>> co = new HashMap<BPNode,Set<BPNode>>();
	
	// event counter
	protected int countEvents = 0;
	
	// map of cutoff events to corresponding events
	protected Map<Event,Event> cutoff2corr = new HashMap<Event,Event>();
	
	// initial branching process
	protected Cut initialBP = null;
	
	private OccurrenceNet occNet = null;
	
	// set of possible extensions updates
	private Set<Event> UPE = null; 
	
	protected List<Transition> totalOrderTs = null;
	
	/**
	 * Protected constructor for technical purposes.
	 */
	protected Unfolding(){}
	
	/**
	 * Constructor. 
	 * 
	 * @param sys A net system to unfold.
	 */
	public Unfolding(NetSystem sys) {
		this(sys, new UnfoldingSetup());
	}
	
	/**
	 * Constructor.
	 * 
	 * @param sys A net system to unfold.
	 * @param setup An unfolding setup.
	 */
	public Unfolding(NetSystem sys, UnfoldingSetup setup) {
		this.sys = sys;
		this.initialBP = new Cut(this.sys);
		this.totalOrderTs = new ArrayList<Transition>(sys.getTransitions());
		this.setup = setup;
		
		// construct unfolding
		if (this.setup.SAFE_OPTIMIZATION)
			this.constructSafe();
		else
			this.construct();
	}

	/**
	 * Construct unfolding.
	 * 
	 * This method closely follows the algorithm described in:
	 * Javier Esparza, Stefan Roemer, Walter Vogler: An Improvement of McMillan's Unfolding Algorithm. Formal Methods in System Design (FMSD) 20(3):285-310 (2002).
	 */
	protected void construct() {
		if (this.sys==null) return;

		// CONSTRUCT INITIAL BRANCHING PROCESS
		Marking M0 = this.sys.getMarking();
		for (Place p : this.sys.getPlaces()) {
			Integer n = M0.get(p);
			for (int i = 0; i<n; i++) {
				Condition c = new Condition(p,null);
				this.addCondition(c);
				this.initialBP.add(c);
			}
		}
		if (!this.addCut(initialBP)) return;
		
		//  Event cutoffIni = null; Event corrIni = null;				// for special handling of events that induce initial markings
		
		// CONSTRUCT UNFOLDING
		Set<Event> pe = getPossibleExtensionsA();						// get possible extensions of initial branching process
		while (!pe.isEmpty()) { 										// while extensions exist
			if (this.countEvents>=this.setup.MAX_EVENTS) return;		// track number of events in unfolding
			Event e = this.setup.ADEQUATE_ORDER.getMinimal(pe);			// event to use for extending unfolding
			
			if (!this.overlap(cutoff2corr.keySet(),e.getLocalConfiguration())) {
				if (!this.addEvent(e)) return;							// add event to unfolding
				
				Event corr = this.checkCutoffA(e);						// check for cutoff event
				if (corr!=null) this.addCutoff(e,corr);					// e is cutoff event
				
				// The following functionality is not captured by Esparza's algorithm !!!
				// The code handles situation when there exist a cutoff event which induces initial marking
				// The identification of such cutoff was postponed to the point until second event which induces initial marking is identified
				//if (corrIni == null) {
					//boolean isCutoffIni = e.getLocalConfiguration().getMarking().equals(this.net.getMarking());
					//if (cutoffIni == null && isCutoffIni) cutoffIni = e;
					//else if (cutoffIni != null && corrIni == null && isCutoffIni) {
						//corrIni = e;
						//this.cutoff2corr.put(cutoffIni, corrIni);
					//}
				//}
				
				pe = getPossibleExtensionsA();							// get possible extensions of branching process
			}
			else {
				pe.remove(e);	
			}
		}
	}
	
	/**
	 * Construct unfolding with optimization for safe systems.
	 * 
	 * This method closely follows the algorithm described in:
	 * Victor Khomenko: Model Checking Based on Prefixes of Petri Net Unfoldings. PhD Thesis. February (2003). 
	 */
	protected void constructSafe() {
		if (this.sys==null) return;

		// CONSTRUCT INITIAL BRANCHING PROCESS
		Marking M0 = this.sys.getMarking();
		for (Place p : M0.toMultiSet()) {
			Condition c = new Condition(p,null);
			this.addConditionSafe(c);
			this.initialBP.add(c);
		}
		if (!this.addCut(initialBP)) return;
		
		// CONSTRUCT UNFOLDING
		Set<Event> pe = getPossibleExtensionsA();						// get possible extensions of initial branching process
		while (!pe.isEmpty()) { 										// while extensions exist
			if (this.countEvents >= this.setup.MAX_EVENTS) return;		// track number of events in unfolding
			Event e = this.setup.ADEQUATE_ORDER.getMinimal(pe);			// event to use for extending unfolding			
			pe.remove(e);												// remove 'e' from the set of possible extensions
			
			if (!this.addEventSafe(e)) return;							// add event 'e' to unfolding
			Event corr = this.checkCutoffA(e);							// check if 'e' is a cutoff event
			if (corr!=null) 											
				this.addCutoff(e,corr);									// record cutoff
			else
				pe.addAll(this.updatePossibleExtensions(e));			// update the set of possible extensions
		}
	}

	private Set<Event> updatePossibleExtensions(Event e) {
		this.UPE = new HashSet<Event>();
		
		Transition u = e.getTransition();
		Set<Transition> upp = new HashSet<Transition>(this.sys.getPostsetTransitions(this.sys.getPostset(u)));
		Set<Place> pu = new HashSet<Place>(this.sys.getPreset(u));
		pu.removeAll(this.sys.getPostset(u));
		upp.removeAll(this.sys.getPostsetTransitions(pu));
		
		for (Transition t : upp) {
			Coset preset = new Coset(this.sys);
			for (Condition b : e.getPostConditions()) {
				if (this.sys.getPreset(t).contains(b.getPlace()))
				preset.add(b);
			}			
			Set<Condition> C = this.getConcurrentConditions(e);
			this.cover(C,t,preset);
		}
		
		return this.UPE;
	}

	private void cover(Set<Condition> C, Transition t, Coset preset) {
		if (this.sys.getPreset(t).size()==preset.size())
			this.UPE.add(new Event(this,t,preset));
		else {
			Set<Place> pre = new HashSet<Place>(this.sys.getPreset(t));
			pre.removeAll(this.getPlaces(preset));
			Place p = pre.iterator().next();
			
			for (Condition d : C) {
				if (d.getPlace().equals(p)) {
					Set<Condition> C2 = new HashSet<Condition>();
					for (Condition dd : C)
						if (this.areConcurrent(d,dd))
							C2.add(dd);
					Coset preset2 = new Coset(this.sys);
					preset2.addAll(preset);
					preset2.add(d);
					this.cover(C2, t, preset2);
				}
			}
		}
	}

	private Set<Place> getPlaces(Coset coset) {
		Set<Place> result = new HashSet<Place>();
		
		for (Condition c : coset)
			result.add(c.getPlace());
		
		return result;
	}

	private Set<Condition> getConcurrentConditions(Event e) {
		Set<Condition> result = new HashSet<Condition>();

		for (Condition c : this.getConditions()) {
			if (this.areConcurrent(e,c))
				result.add(c);
		}
		
		return result;
	}

	/**
	 * Get possible extensions of the unfolding (an extensive way).
	 * 
	 * @return Set of events suitable to extend unfolding.
	 */
	protected Set<Event> getPossibleExtensionsA() {
		Set<Event> result = new HashSet<Event>();
		
		// iterate over all transitions of the originative net
		for (Transition t : this.sys.getTransitions()) {
			// iterate over all places in the preset
			Collection<Place> pre = this.sys.getPreset(t);
			Place p = pre.iterator().next();
			// get cuts that contain conditions that correspond to the place
			Collection<Cut> cuts = this.getCutsWithPlace(p);
			// iterate over cuts
			for (Cut cut : cuts) {
				// get co-set of conditions that correspond to places in the preset (contained in the cut)
				Coset coset = this.containsPlaces(cut,pre);
				if (coset!=null) { // if there exists such a co-set
					// check if there already exists an event that corresponds to the transition with the preset of conditions which equals to coset 
					boolean flag = false;
					if (t2es.get(t)!=null) {
						for (Event e : t2es.get(t)) {
							//if (this.areEqual(e.getPreConditions(),coset)) {
							if (coset.equals(e.getPreConditions())) {
								flag = true;
								break;
							}
						}
					}
					if (!flag) { // we found possible extension !!!
						Event e = new Event(this,t,coset);
						result.add(e);
					}
				}
			}
		}
		
		result.addAll(this.getPossibleExtensionsB(result));
		
		return result;
	}
	
	/**
	 * Get possible extensions (an extension point).
	 * 
	 * @param pe Current possible extensions.
	 * @return Set of events suitable to extend unfolding.
	 */
	protected Set<Event> getPossibleExtensionsB(Set<Event> pe) {
		return new HashSet<Event>();
	}
	
	/**
	 * Check whether a given event is a cutoff event.
	 * 
	 * @param cutoff Event of this unfolding.
	 * @return Corresponding event; <tt>null</tt> if event 'cutoff' is not a cutoff event.
	 */
	protected Event checkCutoffA(Event cutoff) {
		LocalConfiguration lce = cutoff.getLocalConfiguration();
		
		for (Event f : this.getEvents()) {
			if (f.equals(cutoff)) continue;
			LocalConfiguration lcf = f.getLocalConfiguration();	
			if (lce.getMarking().equals(lcf.getMarking()) && this.setup.ADEQUATE_ORDER.isSmaller(lcf, lce))
				return this.checkCutoffB(cutoff,f); // check cutoff extended conditions
		}
		
		return null;
	}
	
	/**
	 * Perform additional checks for event being a cutoff (an extension point).
	 * 
	 * @param cutoff Cutoff event.
	 * @param corr Corresponding event.
	 * @return Corresponding event if e is cutoff; otherwise <tt>null</tt>.
	 */
	protected Event checkCutoffB(Event cutoff, Event corr) {
		return corr;
	}
	
	/**************************************************************************
	 * Manage ordering relations
	 **************************************************************************/
	
	/**
	 * Update concurrency relation based on a given cut.
	 * 
	 * @param cut A cut of this unfolding.
	 */
	private void updateConcurrency(Cut cut) {
		for (Condition c1 : cut) {
			if (this.co.get(c1)==null) this.co.put(c1, new HashSet<BPNode>());
			Event e1 = c1.getPreEvent();
			if (e1 != null && this.co.get(e1)==null) this.co.put(e1, new HashSet<BPNode>());
			for (Condition c2 : cut) {
				if (this.co.get(c2)==null) this.co.put(c2, new HashSet<BPNode>());
				this.co.get(c1).add(c2);
				
				Event e2 = c2.getPreEvent();
				if (e1!=null && e2!=null && !this.ca.get(e1).contains(e2) && !this.ca.get(e2).contains(e1)) this.co.get(e1).add(e2);
				if (!c1.equals(c2) && e1!=null && !this.ca.get(c2).contains(e1) && !this.ca.get(e1).contains(c2)) {
					this.co.get(c2).add(e1);
					this.co.get(e1).add(c2);
				}
			}
		}
	}
	
	private void updateCausalityCondition(Condition c) {
		if (this.ca.get(c)==null)
			this.ca.put(c,new HashSet<BPNode>());
		
		Event e = c.getPreEvent();
		if (e==null) return;
		
		this.ca.get(c).addAll(this.ca.get(e));
		this.ca.get(c).add(e);
	}

	private void updateCausalityEvent(Event e) {
		if (this.ca.get(e)==null)
			this.ca.put(e,new HashSet<BPNode>());
		
		for (Condition c : e.getPreConditions()) {
			this.ca.get(e).addAll(this.ca.get(c));
		}
		this.ca.get(e).addAll(e.getPreConditions());
	}
	
	/**************************************************************************
	 * Useful methods
	 **************************************************************************/
	
	/**
	 * Get cuts that contain conditions that correspond to the place
	 * @param p place
	 * @return collection of cuts that contain conditions that correspond to the place
	 */
	protected Set<Cut> getCutsWithPlace(Place p) {
		Set<Cut> result = new HashSet<Cut>();
		
		Collection<Condition> cs = p2cs.get(p);
		if (cs==null) return result;
		for (Condition c : cs) {
			Collection<Cut> cuts = c2cut.get(c);
			if (cuts!=null) result.addAll(cuts);	
		}
		
		return result;
	}

	/**
	 * Check if cut contains conditions that correspond to places in a collection
	 * TODO remove this method.
	 * 
	 * @param cut cut
	 * @param ps collection of places
	 * @return co-set of conditions that correspond to places in the collection; null if not every place has a corresponding condition 
	 */
	protected Coset containsPlaces(Cut cut, Collection<Place> ps) {
		Coset result = new Coset(this.sys);
		
		for (Place p : ps) {
			boolean flag = false;
			for (Condition c : cut) {
				if (c.getPlace().equals(p)) {
					flag = true;
					result.add(c);
					break;
				}
			}
			if (!flag) return null;
		}

		return result;
	}
	
	/**
	 * Check if one collection of conditions contains another one
	 * TODO remove this method.
	 * 
	 * @param cs1 conditions
	 * @param cs2 conditions
	 * @return true if cs1 contains cs2; otherwise false
	 */
	protected boolean contains(Collection<Condition> cs1, Collection<Condition> cs2) {
		for (Condition c1 : cs2) {
			boolean flag = false;
			for (Condition c2 : cs1) {
				if (c1.equals(c2)) {
					flag = true;
					break;
				}
			}
			if (!flag) return false;
		}
		
		return true;
	}
	
	/**
	 * Add condition to all housekeeping data structures 
	 * @param c condition
	 */
	protected void addCondition(Condition c) {
		this.conds.add(c);
		this.updateCausalityCondition(c);
		
		if (p2cs.get(c.getPlace())!=null)
			p2cs.get(c.getPlace()).add(c);
		else {
			Set<Condition> cs = new HashSet<Condition>();
			cs.add(c);
			p2cs.put(c.getPlace(), cs);
		}
	}
	
	protected void addConditionSafe(Condition c) {
		this.conds.add(c);
		this.updateCausalityCondition(c);
		
		if (p2cs.get(c.getPlace())!=null)
			p2cs.get(c.getPlace()).add(c);
		else {
			Set<Condition> cs = new HashSet<Condition>();
			cs.add(c);
			p2cs.put(c.getPlace(), cs);
		}
	}
	
	/**
	 * Add event to all housekeeping data structures 
	 * @param e event
	 * @return true if event was added successfully; otherwise false
	 */
	protected boolean addEvent(Event e) {
		this.events.add(e);
		this.updateCausalityEvent(e);
		
		if (t2es.get(e.getTransition())!=null) t2es.get(e.getTransition()).add(e);
		else {
			Set<Event> es = new HashSet<Event>();
			es.add(e);
			t2es.put(e.getTransition(), es);
		}
		
		// add conditions that correspond to post-places of transition that corresponds to new event
		Coset postConds = new Coset(this.sys);						// collection of new post conditions
		for (Place s : this.sys.getPostset(e.getTransition())) {	// iterate over places in the postset
			Condition c = new Condition(s,e);	 					// construct new condition
			postConds.add(c);
			this.addCondition(c);									// add condition to unfolding
		}
		e.setPostConditions(postConds);								// set post conditions of event
		
		// compute new cuts of unfolding
		for (Cut cut : c2cut.get(e.getPreConditions().iterator().next())) {
			if (contains(cut,e.getPreConditions())) {
				Cut newCut = new Cut(this.sys,cut);
				newCut.removeAll(e.getPreConditions());
				newCut.addAll(postConds);
				if (!this.addCut(newCut)) return false;
			}
		}
		
		this.countEvents++;
		return true;
	}
	
	protected boolean addEventSafe(Event e) {
		this.events.add(e);
		
		this.updateCausalityEvent(e);
		//this.updateForwardConflicts(e);
		
		if (t2es.get(e.getTransition())!=null) t2es.get(e.getTransition()).add(e);
		else {
			Set<Event> es = new HashSet<Event>();
			es.add(e);
			t2es.put(e.getTransition(), es);
		}
		
		// add conditions that correspond to post-places of transition that corresponds to new event
		Coset postConds = new Coset(this.sys);						// collection of new post conditions
		for (Place s : this.sys.getPostset(e.getTransition())) {	// iterate over places in the postset
			Condition c = new Condition(s,e);	 					// construct new condition
			postConds.add(c);
			this.addConditionSafe(c);								// add condition to unfolding
		}
		e.setPostConditions(postConds);								// set post conditions of event
		
		this.countEvents++;
		return true;
	}

	/**
	 * Add cutoff event
	 * @param e cutoff event
	 * @param corr corresponding event
	 */
	protected void addCutoff(Event e, Event corr) {
		this.cutoff2corr.put(e,corr);
	}

	/**
	 * Add cut to all housekeeping data structures 
	 * @param cut cut
	 * @return true is cut was added successfully; otherwise false;
	 */
	protected boolean addCut(Cut cut) {
		this.updateConcurrency(cut);
		
		Map<Place,Integer> p2i = new HashMap<Place,Integer>();
		
		for (Condition c : cut) {
			// check bound
			Integer i = p2i.get(c.getPlace());
			if (i==null) p2i.put(c.getPlace(),1);
			else {
				if (i == this.setup.MAX_BOUND) return false;
				else p2i.put(c.getPlace(),i+1);
			}
			
			if (c2cut.get(c)!=null) c2cut.get(c).add(cut);
			else {
				Collection<Cut> cuts = new ArrayList<Cut>();
				cuts.add(cut);
				c2cut.put(c,cuts);
			}
		}
		
		return true;
	}
	
	/**************************************************************************
	 * Public interface
	 **************************************************************************/
	
	/**
	 * Get setup of this unfolding.
	 */
	public UnfoldingSetup getSetup() {
		return this.setup;
	}
	
	/**
	 * Get conditions
	 * @return conditions of unfolding
	 */
	public Set<Condition> getConditions() {
		return this.conds;
	}
	
	/**
	 * Get conditions that correspond to place
	 * @return conditions of unfolding that correspond to place
	 */
	public Set<Condition> getConditions(Place p) {
		return this.p2cs.get(p);
	}
	
	/**
	 * Get events
	 * @return events of unfolding
	 */
	public Set<Event> getEvents() {
		return this.events;
	}
	
	/**
	 * Get events that correspond to transition
	 * @return events of unfolding that correspond to transition
	 */
	public Set<Event> getEvents(Transition t) {
		return this.t2es.get(t);
	}
	
	/**
	 * Get originative net system
	 * @return originative net system
	 */
	public NetSystem getNetSystem() {
		return this.sys;
	}
	
	/**
	 * Get the originative net system of this unfolding.
	 * 
	 * @return The originative net system.
	 */
	public PetriNet getOriginativeNetSystem() {
		return this.sys;
	}
	
	/**
	 * Check if two nodes of this unfolding are in the causal relation.
	 * 
	 * @param n1 Node of this unfolding.
	 * @param n2 Node of this unfolding.
	 * @return <tt>true</tt> if 'n1' and 'n2' are in the causal relation; otherwise <tt>false</tt>.
	 */
	public boolean areCausal(BPNode n1, BPNode n2) {
		if (this.ca.get(n2)==null) {
			if (n2 instanceof Event) {
				Event e = (Event) n2;
				if (e.getPreConditions().contains(n1)) return true;
				for (Condition c : e.getPreConditions())
					if (this.ca.get(c).contains(n1))
						return true;
				
				return false;
			}
			else {
				Condition c = (Condition) n2;
				if (c.getPreEvent().equals(n1)) return true;
				if (this.ca.get(c.getPreEvent()).contains(n1)) return true;
				
				return false;
			}
		}
		
		return this.ca.get(n2).contains(n1);
	}
	
	/**
	 * Check if two nodes of this unfolding are in the inverse causal relation.
	 * 
	 * @param n1 Node of this unfolding.
	 * @param n2 Node of this unfolding.
	 * @return <tt>true</tt> if 'n1' and 'n2' are in the inverse causal relation; otherwise <tt>false</tt>.
	 */
	public boolean areInverseCausal(BPNode n1, BPNode n2) {
		return this.areCausal(n2,n1);
	}
	
	/**
	 * Check if two nodes of this unfolding are concurrent.
	 * 
	 * @param n1 Node of this unfolding.
	 * @param n2 Node of this unfolding.
	 * @return <tt>true</tt> if 'n1' and 'n2' are concurrent; otherwise <tt>false</tt>.
	 */
	public boolean areConcurrent(BPNode n1, BPNode n2) {
		Set<BPNode> co = this.CO.get(n1);
		if (co!=null)
			if (co.contains(n2)) return true;
		
		Set<BPNode> notCo = this.notCO.get(n1);
		if (notCo!=null)
			if (notCo.contains(n2)) return false;
		
		boolean result = !this.areCausal(n1,n2) && !this.areInverseCausal(n1,n2) && !this.areInConflict(n1,n2);
		
		if (result)
			this.index(this.CO,n1,n2);
		else
			this.index(this.notCO,n1,n2);
		
		return result;
	}	
	
	/**
	 * Check if two nodes of this unfolding are concurrent.
	 * 
	 * @param n1 Node of this unfolding.
	 * @param n2 Node of this unfolding.
	 * @return <tt>true</tt> if 'n1' and 'n2' are in conflict; otherwise <tt>false</tt>.
	 */
	public boolean areInConflict(BPNode n1, BPNode n2) {
		Set<BPNode> ex = this.EX.get(n1);
		if (ex!=null)
			if (ex.contains(n2)) return true;
		
		Set<BPNode> notEx = this.notEX.get(n1);
		if (notEx!=null)
			if (notEx.contains(n2)) return false;
		
		if (n1.equals(n2)) {
			this.index(this.notEX,n1,n2);
			return false;
		}
		
		Set<BPNode> ca1 = new HashSet<BPNode>(this.ca.get(n1));
		ca1.add(n1);
		Set<BPNode> ca2 = new HashSet<BPNode>(this.ca.get(n2));
		ca2.add(n2);
		
		for (BPNode nn1 : ca1) {
			if (!(nn1 instanceof Event)) continue;
			Event e1 = (Event) nn1;
			for (BPNode nn2 : ca2) {
				if (!(nn2 instanceof Event)) continue;
				Event e2 = (Event) nn2;
				if (e1.equals(e2)) continue;				
				if (!this.overlap(e1.getPreConditions(),e2.getPreConditions())) continue;
				
				this.index(this.EX,n1,n2);
				return true;
			}
		}
		
		this.index(this.notEX,n1,n2);
		return false;
	}
	
	private void index(Map<BPNode,Set<BPNode>> map, BPNode n1, BPNode n2) {
		Set<BPNode> s1 = map.get(n1);
		if (s1==null) {
			Set<BPNode> ss1 = new HashSet<BPNode>();
			ss1.add(n2);
			map.put(n1,ss1);
		}
		else
			s1.add(n2);
		
		Set<BPNode> s2 = map.get(n2);
		if (s2==null) {
			Set<BPNode> ss2 = new HashSet<BPNode>();
			ss2.add(n1);
			map.put(n2,ss2);
		}
		else
			s2.add(n1);
	}

	/**
	 * Check if two sets of BPNodes overlap.
	 * 
	 * @param s1 Set of nodes.
	 * @param s2 Set of nodes.
	 * @return <tt>true</tt> if sets overlap; otherwise <tt>false</tt>.
	 */
	private boolean overlap(Set<? extends BPNode> s1, Set<? extends BPNode> s2) {
		for (BPNode n : s1)
			if (s2.contains(n))
				return true;
		
		return false;
	}

	/**
	 * Get ordering relation between two nodes
	 * @param n1 node
	 * @param n2 node
	 * @return ordering relation between n1 and n2
	 */
	public OrderingRelation getOrderingRelation(BPNode n1, BPNode n2) {
		if (this.areCausal(n1,n2)) return OrderingRelation.CAUSAL;
		if (this.areInverseCausal(n1,n2)) return OrderingRelation.INVERSE_CAUSAL;
		if (this.areInConflict(n1,n2)) return OrderingRelation.CONFLICT;
		return OrderingRelation.CONCURRENT;
	}
	
	/**
	 * Get occurrence net that captures this unfolding
	 * @return occurrence net
	 */
	public OccurrenceNet getOccurrenceNet() {
		this.occNet = new OccurrenceNet(this); 
		return this.occNet; 
	}
	
	/**
	 * Print ordering relations to System.out - for debugging 
	 */
	public void printOrderingRelations() {
		List<BPNode> ns = new ArrayList<BPNode>();
		ns.addAll(this.getConditions());
		ns.addAll(this.getEvents());
		
		System.out.println(" \t");
		for (BPNode n : ns) System.out.print("\t"+n.getName());
		System.out.println();
		
		for (BPNode n1 : ns) {
			System.out.print(n1.getName()+"\t");
			for (BPNode n2 : ns) {
				String rel = "";
				if (this.areCausal(n1,n2)) rel = ">";
				if (this.areInverseCausal(n1,n2)) rel = "<";
				if (this.areConcurrent(n1,n2)) rel = "@";
				if (this.areInConflict(n1,n2)) rel = "#";
				System.out.print(rel + "\t");
			}
			System.out.println();
		}
	}
	
	/**
	 * Get all cutoff events
	 * @return all cutoff events
	 */
	public Set<Event> getCutoffEvents() {
		return this.cutoff2corr.keySet();
	}
	
	/**
	 * Check if event is a cutoff event.
	 * 
	 * @param e An event.
	 * @return <tt>true</tt> if 'e' is a cutoff event; otherwise <tt>false</tt>.
	 */
	public boolean isCutoffEvent(Event e) {
		return this.cutoff2corr.containsKey(e);
	}
	
	/**
	 * Get corresponding event
	 * @param e event
	 * @return corresponding event of e; null if e is not a cutoff event
	 */
	public Event getCorrespondingEvent(Event e) {
		return this.cutoff2corr.get(e);
	}
}