/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_0.planner.logical

import org.neo4j.cypher.internal.compiler.v3_0.planner.PlannerQuery
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans.{IdName, LogicalPlan, NodeLogicalLeafPlan}
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.steps.{countStorePlanner, verifyBestPlan}
import org.neo4j.cypher.internal.frontend.v3_0.{SemanticDirection, Rewriter}

/*
This coordinates PlannerQuery planning and delegates work to the classes that do the actual planning of
QueryGraphs and EventHorizons
 */
case class PlanSingleQuery(planPart: (PlannerQuery, LogicalPlanningContext, Option[LogicalPlan]) => LogicalPlan = planPart,
                           planEventHorizon: LogicalPlanningFunction2[PlannerQuery, LogicalPlan, LogicalPlan] = PlanEventHorizon(),
                           expressionRewriterFactory: (LogicalPlanningContext => Rewriter) = ExpressionRewriterFactory,
                           planWithTail: LogicalPlanningFunction2[LogicalPlan, Option[PlannerQuery], LogicalPlan] = PlanWithTail(),
                           planUpdates: LogicalPlanningFunction2[PlannerQuery, LogicalPlan, LogicalPlan] = PlanUpdates) extends LogicalPlanningFunction1[PlannerQuery, LogicalPlan] {

  override def apply(in: PlannerQuery)(implicit context: LogicalPlanningContext): LogicalPlan = {
    val partPlan = countStorePlanner(in).getOrElse(planPart(in, context, None))

    val planWithEffect =
      if (conflicts(partPlan, in)) context.logicalPlanProducer.planEager(partPlan)
      else partPlan
    val planWithUpdates = planUpdates(in, planWithEffect)(context)

    val projectedPlan = planEventHorizon(in, planWithUpdates)
    val projectedContext = context.recurse(projectedPlan)
    val expressionRewriter = expressionRewriterFactory(projectedContext)
    val completePlan = projectedPlan.endoRewrite(expressionRewriter)

    val finalPlan = planWithTail(completePlan, in.tail)(projectedContext)
    verifyBestPlan(finalPlan,  in)
  }

  /*
   * The first reading leaf node is always stable. However for every preceding leaf node
   * we must make sure there are no updates in this planner query that will match any of the reads.
   * If so we must make that read a RepeatableRead.
   */
  private def conflicts(plan: LogicalPlan, plannerQuery: PlannerQuery): Boolean = {
    if (plannerQuery.updateGraph.isEmpty) false
    else {
      val leaves = plan.leaves.collect {
        case n: NodeLogicalLeafPlan => n.idName
      }


      //if we have unsafe rels we need to check relation overlap and delete
      //overlap immediately
      (hasUnsafeRelationships(plannerQuery) &&
        (relationshipOverlap(plannerQuery) || deleteOverlap(plannerQuery))) ||
        //otherwise only do checks if we have more that one leaf
          leaves.size > 1 && leaves.drop(1).exists(
            nodeOverlap(_, plannerQuery) ||
              relationshipOverlap(plannerQuery) ||
              plannerQuery.updateGraph.setLabelOverlap(plannerQuery.queryGraph) ||
              deleteOverlap(plannerQuery))
    }
  }

  /*
   * Check if the labels of the node with the provided IdName overlaps
   * with the labels updated in this query. This may cause the read to affected
   * by the writes.
   */
  private def nodeOverlap(start: IdName, plannerQuery: PlannerQuery): Boolean = {
    val startLabels = plannerQuery.queryGraph.allKnownLabelsOnNode(start).toSet
    val writeLabels = plannerQuery.updateGraph.labels
    plannerQuery.writesNodes && (
    startLabels.isEmpty || //MATCH ()?
      (startLabels intersect writeLabels).nonEmpty//MATCH (:A) CREATE (:A)?
     )
  }

  /*
   * Unsafe relationships are whatever that may cause unstable
   * iterators when expanding. The unsafe cases are:
   * - (a)-[r]-(b) (undirected)
   * - (a)-[r1]->(b)-[r2]->(c) (multi step)
   * - (a)-[r*]->(b) (variable length)
   */
  private def hasUnsafeRelationships(pq: PlannerQuery) = {
    val allPatterns = pq.queryGraph.allPatternRelationships
    allPatterns.size > 1 ||
      allPatterns.exists(r => r.dir == SemanticDirection.BOTH || !r.length.isSimple)
  }

  /*
   * This will check for overlaps regardless of directions
   */
  private def relationshipOverlap(pq: PlannerQuery): Boolean =
    pq.updateGraph.relationshipOverlap(pq.queryGraph)

  /*
   * This will check for overlaps for both nodes and relationships.
   */
  private def deleteOverlap(pq: PlannerQuery): Boolean =
    pq.updateGraph.deleteOverlap(pq.queryGraph)
}

