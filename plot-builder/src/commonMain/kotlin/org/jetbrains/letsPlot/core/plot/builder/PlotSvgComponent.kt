/*
 * Copyright (c) 2019. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package org.jetbrains.letsPlot.core.plot.builder

import org.jetbrains.letsPlot.commons.event.MouseEventPeer
import org.jetbrains.letsPlot.commons.geometry.DoubleRectangle
import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.commons.intern.gcommon.base.Throwables
import org.jetbrains.letsPlot.commons.logging.PortableLogging
import org.jetbrains.letsPlot.commons.registration.Registration
import org.jetbrains.letsPlot.commons.values.Color
import org.jetbrains.letsPlot.commons.values.SomeFig
import org.jetbrains.letsPlot.core.FeatureSwitch.PLOT_DEBUG_DRAWING
import org.jetbrains.letsPlot.core.plot.base.PlotContext
import org.jetbrains.letsPlot.core.plot.base.layout.TextJustification
import org.jetbrains.letsPlot.core.plot.base.layout.TextJustification.Companion.TextRotation
import org.jetbrains.letsPlot.core.plot.base.layout.Thickness
import org.jetbrains.letsPlot.core.plot.base.render.svg.StrokeDashArraySupport
import org.jetbrains.letsPlot.core.plot.base.render.svg.SvgComponent
import org.jetbrains.letsPlot.core.plot.base.render.svg.Text.HorizontalAnchor
import org.jetbrains.letsPlot.core.plot.base.render.svg.Text.VerticalAnchor
import org.jetbrains.letsPlot.core.plot.base.render.svg.TextLabel
import org.jetbrains.letsPlot.core.plot.base.theme.Theme
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.addTitle
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.captionElementAndTextBounds
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.createTextRectangle
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.drawCaptionDebugInfo
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.drawSubtitleDebugInfo
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.drawTitleDebugInfo
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.subtitleElementAndTextBounds
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.textBoundingBox
import org.jetbrains.letsPlot.core.plot.builder.PlotSvgComponentHelper.titleElementAndTextBounds
import org.jetbrains.letsPlot.core.plot.builder.coord.CoordProvider
import org.jetbrains.letsPlot.core.plot.builder.guide.Orientation
import org.jetbrains.letsPlot.core.plot.builder.layout.LegendBoxesLayout
import org.jetbrains.letsPlot.core.plot.builder.layout.PlotLabelSpecFactory
import org.jetbrains.letsPlot.core.plot.builder.layout.PlotLayoutUtil
import org.jetbrains.letsPlot.core.plot.builder.layout.figure.plot.PlotFigureLayoutInfo
import org.jetbrains.letsPlot.core.plot.builder.presentation.Defaults
import org.jetbrains.letsPlot.core.plot.builder.presentation.LabelSpec
import org.jetbrains.letsPlot.core.plot.builder.presentation.Style
import org.jetbrains.letsPlot.core.plot.builder.tooltip.HorizontalAxisTooltipPosition
import org.jetbrains.letsPlot.core.plot.builder.tooltip.VerticalAxisTooltipPosition
import org.jetbrains.letsPlot.datamodel.svg.dom.SvgGraphicsElement
import org.jetbrains.letsPlot.datamodel.svg.dom.SvgPathDataBuilder
import org.jetbrains.letsPlot.datamodel.svg.dom.SvgPathElement
import org.jetbrains.letsPlot.datamodel.svg.style.StyleSheet

class PlotSvgComponent constructor(
    private val title: String?,
    private val subtitle: String?,
    private val caption: String?,
    private val coreLayersByTile: List<List<GeomLayer>>,
    private val marginalLayersByTile: List<List<GeomLayer>>,
    private val figureLayoutInfo: PlotFigureLayoutInfo,
    private val frameProviderByTile: List<FrameOfReferenceProvider>,
    private val coordProvider: CoordProvider,
    val interactionsEnabled: Boolean,
    private val plotSpecId: String?,
    val theme: Theme,
    val styleSheet: StyleSheet,
    val plotContext: PlotContext
) : SvgComponent() {

    val figureSize: DoubleVector = figureLayoutInfo.figureSize
    val flippedAxis = frameProviderByTile[0].flipAxis
    val mouseEventPeer = MouseEventPeer()

    internal var interactor: PlotInteractor? = null
        set(value) {
            check(field == null) { "interactor can be initialize only once." }
            check(!isBuilt) { "Can't change interactor after plot has already been built." }
            field = value
        }

    internal var liveMapFigures: List<SomeFig> = emptyList()
        private set

    val containsLiveMap: Boolean = coreLayersByTile.flatten().any(GeomLayer::isLiveMap)

    private val hAxisTitle: String? = frameProviderByTile[0].hAxisLabel
    private val vAxisTitle: String? = frameProviderByTile[0].vAxisLabel
    private var isDisposed = false

    override fun clear() {
        // Effectivly disposes the plot component
        // because "interactor" is likely got disposed too,
        // and "interactor" can't be reused.
        isDisposed = true
        super.clear()
    }

    override fun buildComponent() {
        check(!isDisposed) { "Plot can't be rebuild after it was disposed." }
        try {
            buildPlot()
        } catch (e: RuntimeException) {
            LOG.error(e) { "buildPlot" }

            val rootCause = Throwables.getRootCause(e)
            val messages = arrayOf(
                "Error building plot: " + rootCause::class.simpleName, if (rootCause.message != null)
                    "'" + rootCause.message + "'"
                else
                    "<no message>"
            )

            var y = figureSize.y / 2 - 8
            for (s in messages) {
                val errorLabel = TextLabel(s)
                val textColor = when {
                    theme.plot().showBackground() -> theme.plot().textColor()
                    else -> Defaults.TEXT_COLOR
                }
                errorLabel.textColor().set(textColor)
                errorLabel.setFontWeight("normal")
                errorLabel.setFontStyle("normal")
                errorLabel.setHorizontalAnchor(HorizontalAnchor.MIDDLE)
                errorLabel.setVerticalAnchor(VerticalAnchor.CENTER)
                errorLabel.moveTo(figureSize.x / 2, y)
                rootGroup.children().add(errorLabel.rootGroup)
                y += 16.0
            }
        }
    }

    private fun buildPlot() {
        buildPlotComponents()

        reg(object : Registration() {
            override fun doRemove() {
                interactor?.dispose()
                liveMapFigures = emptyList()
            }
        })
    }

    private fun buildPlotComponents() {
        fun SvgPathDataBuilder.rect(rect: DoubleRectangle) = apply {
            moveTo(rect.left, rect.top)
            lineTo(rect.left, rect.bottom)
            lineTo(rect.right, rect.bottom)
            lineTo(rect.right, rect.top)
            closePath()
        }

        val backgroundArea = SvgPathElement()
        val backgroundBorder = SvgPathElement()
        val backgroundLiveMapWindows = mutableListOf<DoubleRectangle>()

        add(backgroundArea)

        // -------------
        val axisEnabled = !containsLiveMap

        val layoutInfo = figureLayoutInfo.plotLayoutInfo

        val plotOuterBounds = figureLayoutInfo.figureLayoutedBounds
        if (DEBUG_DRAWING) {
            drawDebugRect(plotOuterBounds, Color.BLUE, "BLUE: plotOuterBounds")
        }

        val plotOuterBoundsWithoutTitleAndCaption = figureLayoutInfo.figureBoundsWithoutTitleAndCaption
        if (DEBUG_DRAWING) {
            drawDebugRect(
                plotOuterBoundsWithoutTitleAndCaption,
                Color.BLUE,
                "BLUE: plotOuterBoundsWithoutTitleAndCaption"
            )
        }

        val plotAreaOrigin = figureLayoutInfo.plotAreaOrigin
        val plotTheme = theme.plot()

        // build tiles
        val tilesOrigin = plotAreaOrigin
        for (tileLayoutInfo in layoutInfo.tiles) {
            val tileIndex = tileLayoutInfo.trueIndex

            // Create a plot tile.
            val tileFrameProvider = frameProviderByTile[tileIndex]
            val tileFrame = tileFrameProvider.createTileFrame(
                tileLayoutInfo,
                coordProvider,
                DEBUG_DRAWING
            )

            val marginalFrameByMargin: Map<MarginSide, FrameOfReference> = tileFrameProvider
                .createMarginalFrames(
                    tileLayoutInfo,
                    coordProvider,
                    plotTheme.backgroundFill(),
                    DEBUG_DRAWING
                )

            val tile = PlotTile(
                coreLayers = coreLayersByTile[tileIndex],
                marginalLayers = marginalLayersByTile[tileIndex],
                tilesOrigin, tileLayoutInfo,
                plotSpecId,
                theme,
                tileFrame,
                marginalFrameByMargin
            )

            val plotOriginAbsolute = tilesOrigin.add(tileLayoutInfo.offset)
            tile.moveTo(plotOriginAbsolute)

            add(tile)

            tile.liveMapFigure?.run {
                liveMapFigures = liveMapFigures + listOf(this)
            }

            val geomOuterBoundsAbsolute = tileLayoutInfo.geomOuterBounds.add(plotOriginAbsolute)
            val geomInnerBoundsAbsolute = tileLayoutInfo.geomInnerBounds.add(plotOriginAbsolute)
            val geomContentBoundsAbsolute = tileLayoutInfo.geomContentBounds.add(plotOriginAbsolute)

            // axis tooltip should appear on 'outer' bounds:
            val axisOrigin = DoubleVector(
                x = if (layoutInfo.hasLeftAxis) geomOuterBoundsAbsolute.left else geomOuterBoundsAbsolute.right,
                y = if (layoutInfo.hasBottomAxis) geomOuterBoundsAbsolute.bottom else geomOuterBoundsAbsolute.top
            )
            interactor?.onTileAdded(
                tile,
                geomContentBoundsAbsolute,
                tile.targetLocators,
                tile.layerYOrientations,
                axisOrigin,
                hAxisTooltipPosition = if (layoutInfo.hasBottomAxis) HorizontalAxisTooltipPosition.BOTTOM else HorizontalAxisTooltipPosition.TOP,
                vAxisTooltipPosition = if (layoutInfo.hasLeftAxis) VerticalAxisTooltipPosition.LEFT else VerticalAxisTooltipPosition.RIGHT
            )

            if (DEBUG_DRAWING) {
                drawDebugRect(geomInnerBoundsAbsolute, Color.ORANGE, "ORANGE: geomInnerBoundsAbsolute")
            }

            if (containsLiveMap) {
                // Add a hole for the map
                backgroundLiveMapWindows.add(geomInnerBoundsAbsolute)
            }
        }

        if (plotTheme.showBackground()) {
            val plotInset = plotTheme.plotMargins() + Thickness.uniform(plotTheme.backgroundStrokeWidth() / 2)
            val backgroundRect = plotInset.inflateRect(figureLayoutInfo.figureLayoutedBounds)

            val backgroundAreaPath = SvgPathDataBuilder().rect(backgroundRect)
            backgroundLiveMapWindows.forEach(backgroundAreaPath::rect)

            backgroundArea.apply {
                // Do not set stroke - livemap windows (polygon holes) will get stroke too
                fillRule().set(SvgPathElement.FillRule.EVEN_ODD)
                fillColor().set(plotTheme.backgroundFill())
                d().set(backgroundAreaPath.build())
            }

            backgroundBorder.apply {
                fillColor().set(Color.TRANSPARENT)
                strokeColor().set(plotTheme.backgroundColor())
                strokeWidth().set(plotTheme.backgroundStrokeWidth())
                StrokeDashArraySupport.apply(this, plotTheme.backgroundStrokeWidth(), plotTheme.backgroundLineType())
                d().set(SvgPathDataBuilder().rect(backgroundRect).build())

                // Even open path still blocks mouse events. Add pointer-events: none to make links clickable.
                pointerEvents().set(SvgGraphicsElement.PointerEvents.NONE)
            }
        }

        val geomAreaBounds = figureLayoutInfo.geomAreaBounds
        if (DEBUG_DRAWING) {
            drawDebugRect(geomAreaBounds, Color.RED, "RED: geomAreaBounds")
        }

        // plot title, subtitle, caption rectangles:
        //   xxxElementRect - rectangle for element, including margins
        //   xxxTextRect - for text only

        val (plotTitleElementRect, plotTitleTextRect) = titleElementAndTextBounds(
            title,
            plotOuterBounds,
            geomAreaBounds,
            plotTheme
        )
        if (DEBUG_DRAWING) {
            drawTitleDebugInfo(this, caption, plotTitleElementRect, plotTitleTextRect, plotTheme)
        }

        val (subtitleElementRect, subtitleTextRect) = subtitleElementAndTextBounds(
            subtitle,
            plotOuterBounds,
            geomAreaBounds,
            plotTitleElementRect,
            plotTheme
        )
        if (DEBUG_DRAWING) {
            drawSubtitleDebugInfo(this, subtitle, subtitleElementRect, subtitleTextRect, plotTheme)
        }

        val (captionElementRect, captionTextRect) = captionElementAndTextBounds(
            caption,
            plotOuterBounds,
            geomAreaBounds,
            plotTheme
        )
        if (DEBUG_DRAWING) {
            drawCaptionDebugInfo(this, caption, captionElementRect, captionTextRect, plotTheme)
        }

        // add plot title
        plotTitleTextRect?.let {
            addTitle(
                svgComponent = this,
                title,
                labelSpec = PlotLabelSpecFactory.plotTitle(plotTheme),
                justification = plotTheme.titleJustification(),
                boundRect = it,
                className = Style.PLOT_TITLE
            )
        }
        // add plot subtitle
        subtitleTextRect?.let {
            addTitle(
                svgComponent = this,
                subtitle,
                labelSpec = PlotLabelSpecFactory.plotSubtitle(plotTheme),
                justification = plotTheme.subtitleJustification(),
                boundRect = it,
                className = Style.PLOT_SUBTITLE
            )
        }

        val overallTileBounds = PlotLayoutUtil.overallTileBounds(layoutInfo)
            .add(plotAreaOrigin)

        if (DEBUG_DRAWING) {
            drawDebugRect(overallTileBounds, Color.DARK_MAGENTA, "DARK_MAGENTA: overallTileBounds")
        }

        // add axis titles
        if (axisEnabled) {
            if (vAxisTitle != null) {
                val titleOrientation = layoutInfo.tiles.first().axisInfos.vAxisTitleOrientation
                addAxisTitle(
                    vAxisTitle,
                    titleOrientation,
                    overallTileBounds,
                    geomAreaBounds,
                    labelSpec = PlotLabelSpecFactory.axisTitle(theme.verticalAxis(flippedAxis)),
                    justification = theme.verticalAxis(flippedAxis).titleJustification(),
                    margins = theme.verticalAxis(flippedAxis).titleMargins(),
                    plotInset = plotTheme.plotInset(),
                    className = "${Style.AXIS_TITLE}-${theme.verticalAxis(flippedAxis).axis}"
                )
            }
            if (hAxisTitle != null) {
                val titleOrientation = layoutInfo.tiles.first().axisInfos.hAxisTitleOrientation
                addAxisTitle(
                    hAxisTitle,
                    titleOrientation,
                    overallTileBounds,
                    geomAreaBounds,
                    labelSpec = PlotLabelSpecFactory.axisTitle(theme.horizontalAxis(flippedAxis)),
                    justification = theme.horizontalAxis(flippedAxis).titleJustification(),
                    margins = theme.horizontalAxis(flippedAxis).titleMargins(),
                    plotInset = plotTheme.plotInset(),
                    className = "${Style.AXIS_TITLE}-${theme.horizontalAxis(flippedAxis).axis}"
                )
            }
        }

        // add legends
        val legendTheme = theme.legend()
        val legendsBlockInfo = figureLayoutInfo.legendsBlockInfo
        if (!legendTheme.position().isHidden) {
            val legendsBlockInfoLayouted = LegendBoxesLayout(
                outerBounds = plotOuterBoundsWithoutTitleAndCaption,
                innerBounds = geomAreaBounds,
                legendTheme
            ).doLayout(legendsBlockInfo)

            for (boxWithLocation in legendsBlockInfoLayouted.boxWithLocationList) {
                val legendBox = boxWithLocation.legendBox.createLegendBox()
                legendBox.moveTo(boxWithLocation.location)
                add(legendBox)
            }
        }

        // add caption
        captionTextRect?.let {
            addTitle(
                svgComponent = this,
                text = caption,
                labelSpec = PlotLabelSpecFactory.plotCaption(plotTheme),
                justification = plotTheme.captionJustification(),
                boundRect = it,
                className = Style.PLOT_CAPTION
            )
        }

        add(backgroundBorder)
    }

    private fun addAxisTitle(
        text: String,
        orientation: Orientation,
        overallTileBounds: DoubleRectangle,  // tiles union bounds
        overallGeomBounds: DoubleRectangle,  // geom bounds union
        labelSpec: LabelSpec,
        justification: TextJustification,
        margins: Thickness,
        plotInset: Thickness,
        className: String
    ) {
        val referenceRect = when (orientation) {
            Orientation.LEFT,
            Orientation.RIGHT ->
                DoubleRectangle(
                    overallTileBounds.left, overallGeomBounds.top,
                    overallTileBounds.width, overallGeomBounds.height
                )

            Orientation.TOP,
            Orientation.BOTTOM ->
                DoubleRectangle(
                    overallGeomBounds.left, overallTileBounds.top,
                    overallGeomBounds.width, overallTileBounds.height
                )
        }

        val rotation = when (orientation) {
            Orientation.LEFT -> TextRotation.ANTICLOCKWISE
            Orientation.RIGHT -> TextRotation.ANTICLOCKWISE
            else -> null
        }

        val textHeight = PlotLayoutUtil.textDimensions(text, labelSpec).y

        // rectangle for element, including margins
        val axisTitleElementRect = when (orientation) {
            Orientation.LEFT ->
                DoubleRectangle(
                    referenceRect.left - textHeight - margins.width - plotInset.left,
                    referenceRect.top,
                    textHeight + margins.width,
                    referenceRect.height
                )

            Orientation.RIGHT ->
                DoubleRectangle(
                    referenceRect.right + plotInset.right,
                    referenceRect.top,
                    textHeight + margins.width,
                    referenceRect.height
                )

            Orientation.TOP -> DoubleRectangle(
                referenceRect.left,
                referenceRect.top - textHeight - margins.height - plotInset.top,
                referenceRect.width,
                textHeight + margins.height
            )

            Orientation.BOTTOM -> DoubleRectangle(
                referenceRect.left,
                referenceRect.bottom + plotInset.bottom,
                referenceRect.width,
                textHeight + margins.height
            )
        }

        // rectangle for text (without margins)
        val axisTitleTextRect = when {
            orientation.isHorizontal -> {
                createTextRectangle(
                    axisTitleElementRect,
                    topMargin = margins.top,
                    bottomMargin = margins.bottom
                )
            }

            else -> {
                createTextRectangle(
                    axisTitleElementRect,
                    rightMargin = margins.right,
                    leftMargin = margins.left
                )
            }
        }

        addTitle(
            svgComponent = this,
            text,
            labelSpec,
            justification,
            axisTitleTextRect,
            rotation,
            className
        )

        if (DEBUG_DRAWING) {
            drawDebugRect(axisTitleTextRect, Color.LIGHT_BLUE)
            drawDebugRect(axisTitleElementRect, Color.GRAY)
            drawDebugRect(
                textBoundingBox(text, axisTitleTextRect, labelSpec, justification, orientation),
                Color.DARK_GREEN
            )
        }
    }

    companion object {
        private val LOG = PortableLogging.logger(PlotSvgComponent::class)
        private const val DEBUG_DRAWING = PLOT_DEBUG_DRAWING
    }
}
