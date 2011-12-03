// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.osm.visitor.paint.relations;

import java.awt.geom.Path2D;
import java.awt.geom.Path2D.Double;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.Preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.data.Preferences.PreferenceChangedListener;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.osm.visitor.paint.relations.Multipolygon.PolyData.Intersection;

public class Multipolygon {
    /** preference key for a collection of roles which indicate that the respective member belongs to an
     * <em>outer</em> polygon. Default is <tt>outer</tt>.
     */
    static public final String PREF_KEY_OUTER_ROLES = "mappaint.multipolygon.outer.roles";
    /** preference key for collection of role prefixes which indicate that the respective
     *  member belongs to an <em>outer</em> polygon. Default is empty.
     */
    static public final String PREF_KEY_OUTER_ROLE_PREFIXES = "mappaint.multipolygon.outer.role-prefixes";
    /** preference key for a collection of roles which indicate that the respective member belongs to an
     * <em>inner</em> polygon. Default is <tt>inner</tt>.
     */
    static public final String PREF_KEY_INNER_ROLES = "mappaint.multipolygon.inner.roles";
    /** preference key for collection of role prefixes which indicate that the respective
     *  member belongs to an <em>inner</em> polygon. Default is empty.
     */
    static public final String PREF_KEY_INNER_ROLE_PREFIXES = "mappaint.multipolygon.inner.role-prefixes";

    /**
     * <p>Kind of strategy object which is responsible for deciding whether a given
     * member role indicates that the member belongs to an <em>outer</em> or an
     * <em>inner</em> polygon.</p>
     * 
     * <p>The decision is taken based on preference settings, see the four preference keys
     * above.</p>
     * 
     */
    private static class MultipolygonRoleMatcher implements PreferenceChangedListener{
        private final List<String> outerExactRoles = new ArrayList<String>();
        private final List<String> outerRolePrefixes = new ArrayList<String>();
        private final List<String> innerExactRoles = new ArrayList<String>();
        private final List<String> innerRolePrefixes = new ArrayList<String>();

        private void initDefaults() {
            outerExactRoles.clear();
            outerRolePrefixes.clear();
            innerExactRoles.clear();
            innerRolePrefixes.clear();
            outerExactRoles.add("outer");
            innerExactRoles.add("inner");
        }

        private void setNormalized(Collection<String> literals, List<String> target){
            target.clear();
            for(String l: literals) {
                if (l == null) {
                    continue;
                }
                l = l.trim();
                if (!target.contains(l)) {
                    target.add(l);
                }
            }
        }

        private void initFromPreferences() {
            initDefaults();
            if (Main.pref == null) return;
            Collection<String> literals;
            literals = Main.pref.getCollection(PREF_KEY_OUTER_ROLES);
            if (literals != null && !literals.isEmpty()){
                setNormalized(literals, outerExactRoles);
            }
            literals = Main.pref.getCollection(PREF_KEY_OUTER_ROLE_PREFIXES);
            if (literals != null && !literals.isEmpty()){
                setNormalized(literals, outerRolePrefixes);
            }
            literals = Main.pref.getCollection(PREF_KEY_INNER_ROLES);
            if (literals != null && !literals.isEmpty()){
                setNormalized(literals, innerExactRoles);
            }
            literals = Main.pref.getCollection(PREF_KEY_INNER_ROLE_PREFIXES);
            if (literals != null && !literals.isEmpty()){
                setNormalized(literals, innerRolePrefixes);
            }
        }

        @Override
        public void preferenceChanged(PreferenceChangeEvent evt) {
            if (PREF_KEY_INNER_ROLE_PREFIXES.equals(evt.getKey()) ||
                    PREF_KEY_INNER_ROLES.equals(evt.getKey()) ||
                    PREF_KEY_OUTER_ROLE_PREFIXES.equals(evt.getKey()) ||
                    PREF_KEY_OUTER_ROLES.equals(evt.getKey())){
                initFromPreferences();
            }
        }

        public boolean isOuterRole(String role){
            if (role == null) return false;
            for (String candidate: outerExactRoles) {
                if (role.equals(candidate)) return true;
            }
            for (String candidate: outerRolePrefixes) {
                if (role.startsWith(candidate)) return true;
            }
            return false;
        }

        public boolean isInnerRole(String role){
            if (role == null) return false;
            for (String candidate: innerExactRoles) {
                if (role.equals(candidate)) return true;
            }
            for (String candidate: innerRolePrefixes) {
                if (role.startsWith(candidate)) return true;
            }
            return false;
        }
    }

    /*
     * Init a private global matcher object which will listen to preference
     * changes.
     */
    private static MultipolygonRoleMatcher roleMatcher;
    private static MultipolygonRoleMatcher getMultipolygonRoleMatcher() {
        if (roleMatcher == null) {
            roleMatcher = new MultipolygonRoleMatcher();
            if (Main.pref != null){
                roleMatcher.initFromPreferences();
                Main.pref.addPreferenceChangeListener(roleMatcher);
            }
        }
        return roleMatcher;
    }

    public static class JoinedWay {
        private final List<Node> nodes;
        private final boolean selected;

        public JoinedWay(List<Node> nodes, boolean selected) {
            this.nodes = nodes;
            this.selected = selected;
        }

        public List<Node> getNodes() {
            return nodes;
        }

        public boolean isSelected() {
            return selected;
        }

        public boolean isClosed() {
            return nodes.isEmpty() || nodes.get(nodes.size() - 1).equals(nodes.get(0));
        }
    }

    public static class PolyData {
        public enum Intersection {INSIDE, OUTSIDE, CROSSING}

        public final Path2D.Double poly;
        public boolean selected;
        private Rectangle2D bounds;
        private final Collection<Way> ways;
        private final List<Node> nodes;
        private final List<PolyData> inners;

        public PolyData(JoinedWay joinedWay, Collection<Way> refWays) {
            this(joinedWay.getNodes(), joinedWay.isSelected(), refWays);
        }

        public PolyData(List<Node> nodes, boolean selected, Collection<Way> refWays) {
            this.ways = Collections.unmodifiableCollection(refWays);
            this.nodes = Collections.unmodifiableList(nodes);
            this.selected = selected;
            this.inners = new ArrayList<Multipolygon.PolyData>();
            this.poly = new Path2D.Double();
            this.poly.setWindingRule(Path2D.WIND_EVEN_ODD);
            buildPoly();
        }
        
        private void buildPoly() {
            boolean initial = true;
            for (Node n : nodes)
            {
                Point2D p = n.getEastNorth();
                if (initial) {
                    poly.moveTo(p.getX(), p.getY());
                    initial = false;
                } else {
                    poly.lineTo(p.getX(), p.getY());
                }
            }
            poly.closePath();
            for (PolyData inner : inners) {
                appendInner(inner.poly);
            }
        }

        public PolyData(PolyData copy) {
            this.selected = copy.selected;
            this.poly = (Double) copy.poly.clone();
            this.ways = Collections.unmodifiableCollection(copy.ways);
            this.nodes = Collections.unmodifiableList(copy.nodes);
            this.inners = new ArrayList<Multipolygon.PolyData>(copy.inners);
        }
        
        public Intersection contains(Path2D.Double p) {
            int contains = 0;
            int total = 0;
            double[] coords = new double[6];
            for (PathIterator it = p.getPathIterator(null); !it.isDone(); it.next()) {
                switch (it.currentSegment(coords)) {
                    case PathIterator.SEG_MOVETO:
                    case PathIterator.SEG_LINETO:
                        if (poly.contains(coords[0], coords[1])) {
                            contains++;
                        }
                        total++;
                }
            }
            if (contains == total) return Intersection.INSIDE;
            if (contains == 0) return Intersection.OUTSIDE;
            return Intersection.CROSSING;
        }

        public void addInner(PolyData inner) {
            inners.add(inner);
            appendInner(inner.poly);
        }
        
        private void appendInner(Path2D.Double inner) {
            poly.append(inner.getPathIterator(null), false);
        }

        public Path2D.Double get() {
            return poly;
        }

        public Rectangle2D getBounds() {
            if (bounds == null) {
                bounds = poly.getBounds2D();
            }
            return bounds;
        }
        
        public Collection<Way> getWays() {
            return ways;
        }
        
        private void resetPoly() {
            poly.reset();
            buildPoly();
            bounds = null;
        }
        
        public void nodeMoved(NodeMovedEvent event) {
            final Node n = event.getNode();
            boolean innerChanged = false;
            for (PolyData inner : inners) {
                if (inner.nodes.contains(n)) {
                    inner.resetPoly();
                    innerChanged = true;
                }
            }
            if (nodes.contains(n) || innerChanged) {
                resetPoly();
            }
        }
    }

    private final List<Way> innerWays = new ArrayList<Way>();
    private final List<Way> outerWays = new ArrayList<Way>();
    private final List<PolyData> innerPolygons = new ArrayList<PolyData>();
    private final List<PolyData> outerPolygons = new ArrayList<PolyData>();
    private final List<PolyData> combinedPolygons = new ArrayList<PolyData>();

    public Multipolygon(Relation r) {
        load(r);
    }

    private void load(Relation r) {
        MultipolygonRoleMatcher matcher = getMultipolygonRoleMatcher();

        // Fill inner and outer list with valid ways
        for (RelationMember m : r.getMembers()) {
            if (m.getMember().isDrawable()) {
                if (m.isWay()) {
                    Way w = m.getWay();

                    if (w.getNodesCount() < 2) {
                        continue;
                    }

                    if (matcher.isInnerRole(m.getRole())) {
                        innerWays.add(w);
                    } else if (matcher.isOuterRole(m.getRole())) {
                        outerWays.add(w);
                    } else if (!m.hasRole()) {
                        outerWays.add(w);
                    } // Remaining roles ignored
                } // Non ways ignored
            }
        }

        createPolygons(innerWays, innerPolygons);
        createPolygons(outerWays, outerPolygons);
        if (!outerPolygons.isEmpty()) {
            addInnerToOuters();
        }
    }

    private void createPolygons(List<Way> ways, List<PolyData> result) {
        List<Way> waysToJoin = new ArrayList<Way>();
        for (Way way: ways) {
            if (way.isClosed()) {
                result.add(new PolyData(way.getNodes(), way.isSelected(), Collections.singleton(way)));
            } else {
                waysToJoin.add(way);
            }
        }

        for (JoinedWay jw: joinWays(waysToJoin)) {
            result.add(new PolyData(jw, waysToJoin));
        }
    }

    public static Collection<JoinedWay> joinWays(Collection<Way> join)
    {
        Collection<JoinedWay> res = new ArrayList<JoinedWay>();
        Way[] joinArray = join.toArray(new Way[join.size()]);
        int left = join.size();
        while(left != 0)
        {
            Way w = null;
            boolean selected = false;
            List<Node> n = null;
            boolean joined = true;
            while(joined && left != 0)
            {
                joined = false;
                for(int i = 0; i < joinArray.length && left != 0; ++i)
                {
                    if(joinArray[i] != null)
                    {
                        Way c = joinArray[i];
                        if(w == null)
                        { w = c; selected = w.isSelected(); joinArray[i] = null; --left; }
                        else
                        {
                            int mode = 0;
                            int cl = c.getNodesCount()-1;
                            int nl;
                            if(n == null)
                            {
                                nl = w.getNodesCount()-1;
                                if(w.getNode(nl) == c.getNode(0)) {
                                    mode = 21;
                                } else if(w.getNode(nl) == c.getNode(cl)) {
                                    mode = 22;
                                } else if(w.getNode(0) == c.getNode(0)) {
                                    mode = 11;
                                } else if(w.getNode(0) == c.getNode(cl)) {
                                    mode = 12;
                                }
                            }
                            else
                            {
                                nl = n.size()-1;
                                if(n.get(nl) == c.getNode(0)) {
                                    mode = 21;
                                } else if(n.get(0) == c.getNode(cl)) {
                                    mode = 12;
                                } else if(n.get(0) == c.getNode(0)) {
                                    mode = 11;
                                } else if(n.get(nl) == c.getNode(cl)) {
                                    mode = 22;
                                }
                            }
                            if(mode != 0)
                            {
                                joinArray[i] = null;
                                joined = true;
                                if(c.isSelected()) {
                                    selected = true;
                                }
                                --left;
                                if(n == null) {
                                    n = w.getNodes();
                                }
                                n.remove((mode == 21 || mode == 22) ? nl : 0);
                                if(mode == 21) {
                                    n.addAll(c.getNodes());
                                } else if(mode == 12) {
                                    n.addAll(0, c.getNodes());
                                } else if(mode == 22)
                                {
                                    for(Node node : c.getNodes()) {
                                        n.add(nl, node);
                                    }
                                }
                                else /* mode == 11 */
                                {
                                    for(Node node : c.getNodes()) {
                                        n.add(0, node);
                                    }
                                }
                            }
                        }
                    }
                } /* for(i = ... */
            } /* while(joined) */

            if (n == null) {
                n = w.getNodes();
            }

            res.add(new JoinedWay(n, selected));
        } /* while(left != 0) */

        return res;
    }

    public PolyData findOuterPolygon(PolyData inner, List<PolyData> outerPolygons) {

        // First try to test only bbox, use precise testing only if we don't get unique result
        Rectangle2D innerBox = inner.getBounds();
        PolyData insidePolygon = null;
        PolyData intersectingPolygon = null;
        int insideCount = 0;
        int intersectingCount = 0;

        for (PolyData outer: outerPolygons) {
            if (outer.getBounds().contains(innerBox)) {
                insidePolygon = outer;
                insideCount++;
            } else if (outer.getBounds().intersects(innerBox)) {
                intersectingPolygon = outer;
                intersectingCount++;
            }
        }
        
        if (insideCount == 1)
            return insidePolygon;
        else if (intersectingCount == 1)
            return intersectingPolygon;

        PolyData result = null;
        for (PolyData combined : outerPolygons) {
            Intersection c = combined.contains(inner.poly);
            if (c != Intersection.OUTSIDE)
            {
                if (result == null || result.contains(combined.poly) != Intersection.INSIDE) {
                    result = combined;
                }
            }
        }
        return result;
    }

    private void addInnerToOuters()  {

        if (innerPolygons.isEmpty()) {
            combinedPolygons.addAll(outerPolygons);
        } else if (outerPolygons.size() == 1) {
            PolyData combinedOuter = new PolyData(outerPolygons.get(0));
            for (PolyData inner: innerPolygons) {
                combinedOuter.addInner(inner);
            }
            combinedPolygons.add(combinedOuter);
        } else {
            for (PolyData outer: outerPolygons) {
                combinedPolygons.add(new PolyData(outer));
            }

            for (PolyData pdInner: innerPolygons) {
                PolyData o = findOuterPolygon(pdInner, combinedPolygons);
                if (o == null) {
                    o = outerPolygons.get(0);
                }
                o.addInner(pdInner);
            }
        }
        
        // Clear inner and outer polygons to reduce memory footprint
        innerPolygons.clear();
        outerPolygons.clear();
    }

    public List<Way> getOuterWays() {
        return outerWays;
    }

    public List<Way> getInnerWays() {
        return innerWays;
    }
/*
    public List<PolyData> getInnerPolygons() {
        return innerPolygons;
    }

    public List<PolyData> getOuterPolygons() {
        return outerPolygons;
    }
*/
    public List<PolyData> getCombinedPolygons() {
        return combinedPolygons;
    }

}
