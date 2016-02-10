/*
 * This file is part of Kiama.
 *
 * Copyright (C) 2014-2016 Anthony M Sloane, Macquarie University.
 *
 * Kiama is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Kiama is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Kiama.  (See files COPYING and COPYING.LESSER.)  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package org.bitbucket.inkytonik.kiama
package relation

/**
 * Exception thrown if a `TreeRelation` operation tries to use a node that
 * is not in the tree to which the relation applies.
 *
 * For the time-being, the exception does not indicate which tree is involved
 * because it's not clear how to identify the tree briefly in a short message.
 * The stack trace that accompanies the exception should be sufficient.
 */
case class NodeNotInTreeException[T](t : T) extends Exception(s"node not in tree: $t")

/**
 * A bridge node in a tree structure which connects to another structure.
 * Bridges are not traversed when determining the tree structure.
 */
case class Bridge[T](cross : T)

/**
 * Relational representations of trees built out of hierarchical `Product`
 * instances. Typically, the nodes are instances of case classes.
 *
 * The type `T` is the common base type of the tree nodes. The type `R` is a
 * sub-type of `T` that is the type of the root node.
 *
 * The `originalRoot` value should be the root of a finite, acyclic structure
 * that contains only `Product` instances of type `T` (unless they are skipped,
 * see below). If the structure reachable from `originalRoot` is actually a tree
 * (i.e., contains no shared nodes) then the field `root` will be the same as
 * `originalRoot`.
 *
 * On the other hand, if the structure contains shared nodes, then `root` will
 * be the root of a new structure which duplicates the original structure but
 * removes the sharing. In other words, shared nodes will be copied.
 *
 * The `child` relation of a tree is defined to skip certain nodes.
 * Specifically, if a node of type `T` is wrapped in a `Some` of an option,
 * a `Left` or `Right` of an `Either`, a tuple up to size four, or a
 * `TraversableOnce`, then those wrappers are ignored. E.g., if `t1`
 * is `Some (t2)` where both `t1` and `t2` are of type `T`, then `t1`
 * will be `t2`'s parent, not the `Some` value.
 *
 * Thanks to Len Hamey for the idea to use lazy cloning to restore the tree
 * structure instead of requiring that the input trees contain no sharing.
 */
class Tree[T <: Product, +R <: T](val originalRoot : R) {

    tree =>

    import org.bitbucket.inkytonik.kiama.rewriting.Strategy
    import org.bitbucket.inkytonik.kiama.rewriting.Cloner.lazyclone
    import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.{all, attempt, rule}
    import org.bitbucket.inkytonik.kiama.util.Comparison.{contains, same}
    import Relation.fromOneStepGraph
    import Tree.treeChildren

    /**
     * A version of `bottomup` that doesn't traverse bridges.
     */
    def bottomupNoBridges(s : Strategy) : Strategy =
        rule[Bridge[_]] { case b => b } <+
            (all(bottomupNoBridges(s)) <* s)

    /**
     * A version of `everywherebu` that doesn't traverse bridges.
     */
    def everywherebuNoBridges(s : Strategy) : Strategy =
        bottomupNoBridges(attempt(s))

    /**
     * The root node of the tree. The root node will be different from the
     * original root if any nodes in the original tree are shared, since
     * they will be cloned as necessary to yield a proper tree structure.
     * If there is no sharing then `root` will be same as `originalRoot`.
     * Bridges to other structures will not be traversed.
     */
    lazy val root = lazyclone(originalRoot, everywherebuNoBridges)

    /**
     * The graph of the child relation for this tree.
     */
    lazy val childGraph = fromOneStepGraph[T](root, treeChildren)

    /**
     * A tree relation is a binary relation on tree nodes with an extra property
     * that the `image` operation throws a `NodeNotInTreeException` exception if
     * it is applied to a node that is not in this tree.
     */
    class TreeRelation[V, W](val graph : Vector[(V, W)]) extends RelationLike[V, W, TreeRelation] {

        val companion = TreeRelationFactory

        /**
         * Return the image of a node in this tree. As for normal relations,
         * except that it throws `NodeNotInTreeException` if the node is not
         * actually in the tree on which this relation is defined.
         */
        override def image(v : V) : Vector[W] =
            tree.whenContains(v, super.image(v))

    }

    /**
     * Relation factory for tree relations.
     */
    object TreeRelationFactory extends RelationFactory[TreeRelation] {

        def fromGraph[V, W](graph : Vector[(V, W)]) : TreeRelation[V, W] =
            new TreeRelation[V, W](graph)

    }

    /**
     * The nodes that occur in this tree.
     */
    val nodes : Vector[T] =
        root +: (childGraph.map(_._2))

    /**
     * If the tree contains node `u` return `v`, otherwise throw a
     * `NodeNotInTreeException`.
     */
    def whenContains[U, V](u : U, v : V) : V =
        if (same(u, root) || (contains(nodes, u)))
            v
        else
            throw new NodeNotInTreeException(u)

    /**
     * The basic relation between a node and its children. All of the
     * other relations are derived from this one. This relation preserves
     * order in the sense that the graph of this relation records the
     * children of a particular node in the order they appear in the
     * tree.
     */
    lazy val child : TreeRelation[T, T] = {

        // Compute the child relation
        val rel = new TreeRelation(childGraph)

        // As a safety check, we make sure that values are not children
        // of more than one parent.
        val msgBuilder = new StringBuilder
        for ((c, ps) <- rel.projRange.graph) {
            if (ps.length > 1) {
                msgBuilder ++= s"child $c has multiple parents:\n"
                for (p <- ps) {
                    msgBuilder ++= s"  $p\n"
                }
            }
        }
        if (!msgBuilder.isEmpty)
            sys.error("Tree creation: illegal tree structure:\n" + msgBuilder.result)

        // All ok
        rel

    }

    // Derived relations

    /**
     * A relation that relates a node to its first child.
     */
    lazy val firstChild : TreeRelation[T, T] =
        new TreeRelation(
            child.projDomain.graph.map { case (t, ts) => (t, ts.head) }
        )

    /**
     * A relation that relates a node to its last child.
     */
    lazy val lastChild : TreeRelation[T, T] =
        new TreeRelation(
            child.projDomain.graph.map { case (t, ts) => (t, ts.last) }
        )

    /**
     * A relation that relates a node to its next sibling. Inverse of
     * the `prev` relation.
     */
    lazy val next : TreeRelation[T, T] = {

        def nextPairs(ts : Vector[T]) : Vector[(T, T)] =
            ts match {
                case t1 +: (rest @ (t2 +: _)) =>
                    (t1, t2) +: nextPairs(rest)
                case _ =>
                    Vector()
            }

        new TreeRelation(
            child.projDomain.graph.flatMap(p => nextPairs(p._2))
        )

    }

    /**
     * A relation that relates a node to its parent. Inverse of the
     * `child` relation.
     */
    lazy val parent : TreeRelation[T, T] =
        child.inverse

    /**
     * A relation that relates a node to its previous sibling. Inverse
     * of the `next` relation.
     */
    lazy val prev : TreeRelation[T, T] =
        next.inverse

    /**
     * A relation that relates a node to all of its siblings.
     */
    lazy val siblings : TreeRelation[T, T] =
        new TreeRelation[T, T](Vector((root, root))) union (child.compose(parent))

    // Predicates derived from the relations

    /**
     * Return the index of `t` in the children of `t's` parent node.
     * Counts from zero.
     */
    def index(t : T) : Int =
        siblings.withDomain(t).index(t) match {
            case Vector(i) =>
                i
            case is =>
                sys.error(s"index: non-singleton index $is for $t")
        }

    /**
     * Return whether or not `t` is a first child.
     */
    def isFirst(t : T) : Boolean =
        whenContains(t, !prev.containsInDomain(t))

    /**
     * Return whether or not `t` is a last child.
     */
    def isLast(t : T) : Boolean =
        whenContains(t, !next.containsInDomain(t))

    /**
     * Return whether or not `t` is the root of this tree.
     */
    def isRoot(t : T) : Boolean =
        whenContains(
            t,
            (t, root) match {
                case (tr : AnyRef, rootr : AnyRef) =>
                    tr eq rootr
            }
        )

}

/**
 * Companion object for trees.
 */
object Tree {

    import scala.annotation.tailrec
    import scala.collection.immutable.Queue

    /**
     * Return whether this node is a leaf node or not.
     */
    def isLeaf[T <: Product](t : T) : Boolean = {
        for (desc <- t.productIterator) {
            desc match {
                case _ : Option[_] | _ : Either[_, _] | _ : Tuple1[_] |
                    _ : Tuple2[_, _] | _ : Tuple3[_, _, _] | _ : Tuple4[_, _, _, _] =>
                // Do nothing
                case _ : Product =>
                    return false
                case _ =>
                // Do nothing
            }
        }
        true
    }

    /**
     * Return a vector of the children of `t`, skipping values that do not
     * contribute directly to the tree structure. See the documentation of the
     * `Tree` class for a detailed explanation of values that are skipped by
     * this method.
     */
    def treeChildren[T <: Product](t : T) : Vector[T] = {

        @tailrec
        def loop(pending : Queue[Any], children : Vector[T]) : Vector[T] =
            if (pending.isEmpty)
                children
            else {
                val candidate = pending.front
                val rest = pending.tail
                candidate match {
                    case _ : Bridge[_] =>
                        // ignore
                        loop(rest, children)

                    case Some(n) =>
                        loop(n +: rest, children)
                    case None =>
                        // ignore
                        loop(rest, children)

                    case Left(l) =>
                        loop(l +: rest, children)
                    case Right(r) =>
                        loop(r +: rest, children)

                    case Tuple1(a) =>
                        loop(a +: rest, children)
                    case (a, b) =>
                        loop(List(a, b) ++: rest, children)
                    case (a, b, c) =>
                        loop(List(a, b, c) ++: rest, children)
                    case (a, b, c, d) =>
                        loop(List(a, b, c, d) ++: rest, children)

                    case s : TraversableOnce[_] =>
                        loop(s ++: rest, children)

                    case p : Product =>
                        loop(rest, children :+ (p.asInstanceOf[T]))

                    case _ =>
                        // ignore
                        loop(rest, children)
                }
            }

        loop(Queue(t.productIterator), Vector())

    }

}