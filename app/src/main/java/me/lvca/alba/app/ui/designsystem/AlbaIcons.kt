package me.lvca.alba.app.ui.designsystem

// Material Symbols path data is licensed under Apache-2.0; see THIRD_PARTY_NOTICES.md.

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme

/**
 * Alba's one icon family: Material Symbols Rounded at weight 400, grade 0 and 24dp optical size, as Google Photos and
 * Android 16 use. Glyphs are outlined; the `Filled` ones and [Favorite] are FILL 1, only for selected or active states
 * (the current destination, a favourite). [PlayArrow] is the one exception and always solid, as Google Photos and
 * Apple draw it: an outlined triangle reads as disabled over photos. Directional glyphs mirror in right-to-left
 * layouts. The collection glyphs
 * in `res/drawable` are the same family. Don't use `Icons.*` from material-icons or another family.
 */
internal object AlbaIcons {
    /** Material Symbols Rounded add, outlined. */
    val Add: ImageVector by lazy {
        symbol(
            "Add",
            "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760q17 0 28.5 11.5T520-7" +
            "20v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v" +
            "-200Z",
        )
    }

    /** Material Symbols Rounded account_box, outlined. */
    val AccountBox: ImageVector by lazy {
        symbol(
            "AccountBox",
            "M200-246q54-53 125.5-83.5T480-360q83 0 154.5 30.5T760-246v-514H200v514Zm280-194q58 0 99-41t41-99q0-58-41-99t-99-" +
            "41q-58 0-99 41t-41 99q0 58 41 99t99 41ZM200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56." +
            "5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm80-80h400v-10q-42-35-93-52.5T480-280q-56 0-107 17.5T280-210v10Zm2" +
            "00-320q-25 0-42.5-17.5T420-580q0-25 17.5-42.5T480-640q25 0 42.5 17.5T540-580q0 25-17.5 42.5T480-520Zm0 17Z",
        )
    }

    /** Material Symbols Rounded account_circle, outlined. */
    val AccountCircle: ImageVector by lazy {
        symbol(
            "AccountCircle",
            "M234-276q51-39 114-61.5T480-360q69 0 132 22.5T726-276q35-41 54.5-93T800-480q0-133-93.5-226.5T480-800q-133 0-226." +
            "5 93.5T160-480q0 59 19.5 111t54.5 93Zm246-164q-59 0-99.5-40.5T340-580q0-59 40.5-99.5T480-720q59 0 99.5 40.5T620-" +
            "580q0 59-40.5 99.5T480-440Zm0 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-" +
            "85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q53 " +
            "0 100-15.5t86-44.5q-39-29-86-44.5T480-280q-53 0-100 15.5T294-220q39 29 86 44.5T480-160Zm0-360q26 0 43-17t17-43q0" +
            "-26-17-43t-43-17q-26 0-43 17t-17 43q0 26 17 43t43 17Zm0-60Zm0 360Z",
        )
    }

    /** Material Symbols Rounded arrow_back, outlined. */
    val ArrowBack: ImageVector by lazy {
        symbol(
            "ArrowBack",
            "m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-" +
            "264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z",
            autoMirror = true,
        )
    }

    /** Material Symbols Rounded check, outlined. */
    val Check: ImageVector by lazy {
        symbol(
            "Check",
            "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T18" +
            "3-497q12-12 28.5-12t28.5 12l142 143Z",
        )
    }

    /** Material Symbols Rounded chevron_right, outlined. */
    val ChevronRight: ImageVector by lazy {
        symbol(
            "ChevronRight",
            "M504-480 348-636q-11-11-11-28t11-28q11-11 28-11t28 11l184 184q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L404-268q-11 1" +
            "1-28 11t-28-11q-11-11-11-28t11-28l156-156Z",
            autoMirror = true,
        )
    }

    /** Material Symbols Rounded close, outlined. */
    val Close: ImageVector by lazy {
        symbol(
            "Close",
            "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28q11-11 28-11t28 11l196" +
            " 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424" +
            "Z",
        )
    }

    /** Material Symbols Rounded cloud_done, outlined. */
    val CloudDone: ImageVector by lazy {
        symbol(
            "CloudDone",
            "m413-395-56-56q-12-12-28-12t-28 12q-12 12-12 28.5t12 28.5l85 86q12 12 28 12t28-12l169-169q12-12 12-29t-12-29q-12" +
            "-12-29-12t-29 12L413-395ZM260-160q-91 0-155.5-63T40-377q0-78 47-139t123-78q25-92 100-149t170-57q117 0 198.5 81.5" +
            "T760-520q69 8 114.5 59.5T920-340q0 75-52.5 127.5T740-160H260Zm0-80h480q42 0 71-29t29-71q0-42-29-71t-71-29h-60v-8" +
            "0q0-83-58.5-141.5T480-720q-83 0-141.5 58.5T280-520h-20q-58 0-99 41t-41 99q0 58 41 99t99 41Zm220-240Z",
        )
    }

    /** Material Symbols Rounded cloud_off, outlined. */
    val CloudOff: ImageVector by lazy {
        symbol(
            "CloudOff",
            "M260-160q-92 0-156-64T40-380q0-77 47.5-137T210-594q3-8 6-15.5t6-16.5L84-764q-11-11-11-28t11-28q11-11 28-11t28 11" +
            "l680 680q11 11 11.5 27.5T820-84q-11 11-27.5 11.5T764-83l-78-77H260Zm0-80h346L284-562q-2 11-3 21t-1 21h-20q-58 0-" +
            "99 41t-41 99q0 58 41 99t99 41Zm185-161Zm419 191-58-56q17-14 25.5-32.5T840-340q0-42-29-71t-71-29h-60v-80q0-83-58." +
            "5-141.5T480-720q-27 0-52 6.5T380-693l-58-58q35-24 74.5-36.5T480-800q117 0 198.5 81.5T760-520q69 8 114.5 59.5T920" +
            "-340q0 39-15 72.5T864-210ZM593-479Z",
        )
    }

    /** Material Symbols Rounded cloud, outlined. */
    val CloudQueue: ImageVector by lazy {
        symbol(
            "CloudQueue",
            "M260-160q-91 0-155.5-63T40-377q0-78 47-139t123-78q25-92 100-149t170-57q117 0 198.5 81.5T760-520q69 8 114.5 59.5T" +
            "920-340q0 75-52.5 127.5T740-160H260Zm0-80h480q42 0 71-29t29-71q0-42-29-71t-71-29h-60v-80q0-83-58.5-141.5T480-720" +
            "q-83 0-141.5 58.5T280-520h-20q-58 0-99 41t-41 99q0 58 41 99t99 41Zm220-240Z",
        )
    }

    /** Material Symbols Rounded cloud_sync, outlined. */
    val CloudSync: ImageVector by lazy {
        symbol(
            "CloudSync",
            "M240-480q0 54 21.5 99.5T320-302v-58q0-17 11.5-28.5T360-400q17 0 28.5 11.5T400-360v160q0 17-11.5 28.5T360-160H200" +
            "q-17 0-28.5-11.5T160-200q0-17 11.5-28.5T200-240h69q-51-44-80-106t-29-134q0-95 49.5-171T338-767q14-8 29-.5t20 23." +
            "5q5 16-1.5 31T365-690q-56 31-90.5 86.5T240-480Zm360 320q-50 0-85-35t-35-85q0-48 33-82.5t81-36.5q17-36 50.5-58.5T" +
            "720-480q53 0 91.5 34.5T858-360q42 0 72 29t30 70q0 42-29 71.5T860-160H600Zm40-498v58q0 17-11.5 28.5T600-560q-17 0" +
            "-28.5-11.5T560-600v-160q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720h-69q33 29 57.5 " +
            "66.5T786-572q5 16-4 29t-26 16q-17 3-30.5-6.5T706-559q-10-29-27-54t-39-45Zm-40 418h260q8 0 14-6t6-14q0-8-6-14t-14" +
            "-6h-70v-50q0-29-20.5-49.5T720-400q-29 0-49.5 20.5T650-330v10h-50q-17 0-28.5 11.5T560-280q0 17 11.5 28.5T600-240Z" +
            "m120-80Z",
        )
    }

    /** Material Symbols Rounded cloud_upload, outlined. */
    val CloudUpload: ImageVector by lazy {
        symbol(
            "CloudUpload",
            "m440-446-36 35q-11 11-27.5 11T348-412q-11-11-11-28t11-28l104-104q12-12 28-12t28 12l104 104q11 11 11.5 27.5T612-4" +
            "12q-11 11-27.5 11.5T556-411l-36-35v206h220q42 0 71-29t29-71q0-42-29-71t-71-29h-60v-80q0-83-58.5-141.5T480-720q-8" +
            "3 0-141.5 58.5T280-520h-20q-58 0-99 41t-41 99q0 58 41 99t99 41h60q17 0 28.5 11.5T360-200q0 17-11.5 28.5T320-160h" +
            "-60q-91 0-155.5-63T40-377q0-78 47-139t123-78q25-92 100-149t170-57q117 0 198.5 81.5T760-520q69 8 114.5 59.5T920-3" +
            "40q0 75-52.5 127.5T740-160H520q-33 0-56.5-23.5T440-240v-206Zm40 6Z",
        )
    }

    /** Material Symbols Rounded date_range, outlined. */
    val DateRange: ImageVector by lazy {
        symbol(
            "DateRange",
            "M320-400q-17 0-28.5-11.5T280-440q0-17 11.5-28.5T320-480q17 0 28.5 11.5T360-440q0 17-11.5 28.5T320-400Zm160 0q-17" +
            " 0-28.5-11.5T440-440q0-17 11.5-28.5T480-480q17 0 28.5 11.5T520-440q0 17-11.5 28.5T480-400Zm160 0q-17 0-28.5-11.5" +
            "T600-440q0-17 11.5-28.5T640-480q17 0 28.5 11.5T680-440q0 17-11.5 28.5T640-400ZM200-80q-33 0-56.5-23.5T120-160v-5" +
            "60q0-33 23.5-56.5T200-800h40v-40q0-17 11.5-28.5T280-880q17 0 28.5 11.5T320-840v40h320v-40q0-17 11.5-28.5T680-880" +
            "q17 0 28.5 11.5T720-840v40h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Zm0-480" +
            "h560v-80H200v80Zm0 0v-80 80Z",
        )
    }

    /** Material Symbols Rounded delete, outlined. */
    val DeleteOutline: ImageVector by lazy {
        symbol(
            "DeleteOutline",
            "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h" +
            "160q17 0 28.5 11.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 56.5T680-120H280Zm400" +
            "-600H280v520h400v-520ZM400-280q17 0 28.5-11.5T440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 11.5T360-600v280q0 " +
            "17 11.5 28.5T400-280Zm160 0q17 0 28.5-11.5T600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 " +
            "11.5 28.5T560-280ZM280-720v520-520Z",
        )
    }

    /** Material Symbols Rounded delete_sweep, outlined. */
    val DeleteSweep: ImageVector by lazy {
        symbol(
            "DeleteSweep",
            "M200-200q-33 0-56.5-23.5T120-280v-360q-17 0-28.5-11.5T80-680q0-17 11.5-28.5T120-720h120v-20q0-17 11.5-28.5T280-7" +
            "80h80q17 0 28.5 11.5T400-740v20h120q17 0 28.5 11.5T560-680q0 17-11.5 28.5T520-640v360q0 33-23.5 56.5T440-200H200" +
            "Zm440-40q-17 0-28.5-11.5T600-280q0-17 11.5-28.5T640-320h80q17 0 28.5 11.5T760-280q0 17-11.5 28.5T720-240h-80Zm0-" +
            "160q-17 0-28.5-11.5T600-440q0-17 11.5-28.5T640-480h160q17 0 28.5 11.5T840-440q0 17-11.5 28.5T800-400H640Zm0-160q" +
            "-17 0-28.5-11.5T600-600q0-17 11.5-28.5T640-640h200q17 0 28.5 11.5T880-600q0 17-11.5 28.5T840-560H640Zm-440-80v36" +
            "0h240v-360H200Z",
        )
    }

    /** Material Symbols Rounded download_for_offline, outlined. */
    val DownloadForOffline: ImageVector by lazy {
        symbol(
            "DownloadForOffline",
            "M320-280h320q17 0 28.5-11.5T680-320q0-17-11.5-28.5T640-360H320q-17 0-28.5 11.5T280-320q0 17 11.5 28.5T320-280Zm1" +
            "20-274-36-35q-11-11-27.5-11T348-588q-11 11-11 28t11 28l104 104q12 12 28 12t28-12l104-104q11-11 11.5-27.5T612-588" +
            "q-11-11-27.5-11.5T556-589l-36 35v-126q0-17-11.5-28.5T480-720q-17 0-28.5 11.5T440-680v126Zm40 474q-83 0-156-31.5T" +
            "197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127" +
            "T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 " +
            "93t-93 227q0 134 93 227t227 93Zm0-320Z",
        )
    }

    /** Material Symbols Rounded edit, outlined. */
    val Edit: ImageVector by lazy {
        symbol(
            "Edit",
            "M200-200h57l391-391-57-57-391 391v57Zm-40 80q-17 0-28.5-11.5T120-160v-97q0-16 6-30.5t17-25.5l505-504q12-11 26.5-" +
            "17t30.5-6q16 0 31 6t26 18l55 56q12 11 17.5 26t5.5 30q0 16-5.5 30.5T817-647L313-143q-11 11-25.5 17t-30.5 6h-97Zm6" +
            "00-584-56-56 56 56Zm-141 85-28-29 57 57-29-28Z",
        )
    }

    /** Material Symbols Rounded mail, outlined. */
    val Email: ImageVector by lazy {
        symbol(
            "Email",
            "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-16" +
            "0H160Zm640-480L501-453q-5 3-10.5 4.5T480-447q-5 0-10.5-1.5T459-453L160-640v400h640v-400ZM480-520l320-200H160l320" +
            " 200ZM160-640v10-59 1-32 32-.5 58.5-10 400-400Z",
        )
    }

    /** Material Symbols Rounded error, outlined. */
    val ErrorOutline: ImageVector by lazy {
        symbol(
            "ErrorOutline",
            "M480-280q17 0 28.5-11.5T520-320q0-17-11.5-28.5T480-360q-17 0-28.5 11.5T440-320q0 17 11.5 28.5T480-280Zm0-160q17 " +
            "0 28.5-11.5T520-480v-160q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v160q0 17 11.5 28.5T480-440Zm0 360q-83 0-" +
            "156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54" +
            " 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-1" +
            "34 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
        )
    }

    /** Material Symbols Rounded exit_to_app, outlined. */
    val ExitToApp: ImageVector by lazy {
        symbol(
            "ExitToApp",
            "M200-120q-33 0-56.5-23.5T120-200v-120q0-17 11.5-28.5T160-360q17 0 28.5 11.5T200-320v120h560v-560H200v120q0 17-11" +
            ".5 28.5T160-600q-17 0-28.5-11.5T120-640v-120q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56." +
            "5T760-120H200Zm266-320H160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h306l-74-74q-12-12-11.5-28t11.5-28q12-1" +
            "2 28.5-12.5T449-651l143 143q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L449-309q-12 12-28.5 11.5T392-310q-11-12-11.5-28" +
            "t11.5-28l74-74Z",
            autoMirror = true,
        )
    }

    /** Material Symbols Rounded favorite, filled. */
    val Favorite: ImageVector by lazy {
        symbol(
            "Favorite",
            "M480-147q-14 0-28.5-5T426-168l-69-63q-106-97-191.5-192.5T80-634q0-94 63-157t157-63q53 0 100 22.5t80 61.5q33-39 8" +
            "0-61.5T660-854q94 0 157 63t63 157q0 115-85 211T602-230l-68 62q-11 11-25.5 16t-28.5 5Z",
        )
    }

    /** Material Symbols Rounded favorite, outlined. */
    val FavoriteBorder: ImageVector by lazy {
        symbol(
            "FavoriteBorder",
            "M480-147q-14 0-28.5-5T426-168l-69-63q-106-97-191.5-192.5T80-634q0-94 63-157t157-63q53 0 100 22.5t80 61.5q33-39 8" +
            "0-61.5T660-854q94 0 157 63t63 157q0 115-85 211T602-230l-68 62q-11 11-25.5 16t-28.5 5Zm-38-543q-29-41-62-62.5T300" +
            "-774q-60 0-100 40t-40 100q0 52 37 110.5T285.5-410q51.5 55 106 103t88.5 79q34-31 88.5-79t106-103Q726-465 763-523." +
            "5T800-634q0-60-40-100t-100-40q-47 0-80 21.5T518-690q-7 10-17 15t-21 5q-11 0-21-5t-17-15Zm38 189Z",
        )
    }

    /** Material Symbols Rounded info, outlined. */
    val Info: ImageVector by lazy {
        symbol(
            "Info",
            "M480-280q17 0 28.5-11.5T520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 11.5T440-480v160q0 17 11.5 28.5T480-280Zm" +
            "0-320q17 0 28.5-11.5T520-640q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640q0 17 11.5 28.5T480-600Zm0 520q-83 0-" +
            "156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54" +
            " 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-1" +
            "34 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
        )
    }

    /** Material Symbols Rounded vpn_key, outlined. */
    val Key: ImageVector by lazy {
        symbol(
            "Key",
            "M280-240q-100 0-170-70T40-480q0-100 70-170t170-70q66 0 121 33t87 87h352q33 0 56.5 23.5T920-520v80q0 33-23.5 56.5" +
            "T840-360v40q0 33-23.5 56.5T760-240h-80q-33 0-56.5-23.5T600-320v-40H488q-32 54-87 87t-121 33Zm0-80q66 0 106-40.5t" +
            "48-79.5h246v120h80v-120h80v-80H434q-8-39-48-79.5T280-640q-66 0-113 47t-47 113q0 66 47 113t113 47Zm0-80q33 0 56.5" +
            "-23.5T360-480q0-33-23.5-56.5T280-560q-33 0-56.5 23.5T200-480q0 33 23.5 56.5T280-400Zm0-80Z",
        )
    }

    /** Material Symbols Rounded lock, outlined. */
    val Lock: ImageVector by lazy {
        symbol(
            "Lock",
            "M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-7" +
            "20v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560" +
            "-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-5" +
            "0 0-85 35t-35 85v80ZM240-160v-400 400Z",
        )
    }

    /** Material Symbols Rounded more_vert, outlined. */
    val MoreVert: ImageVector by lazy {
        symbol(
            "MoreVert",
            "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm0-240q-33" +
            " 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5" +
            "T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Z",
        )
    }

    /** Material Symbols Rounded pause, outlined. */
    val Pause: ImageVector by lazy {
        symbol(
            "Pause",
            "M600-200q-33 0-56.5-23.5T520-280v-400q0-33 23.5-56.5T600-760h80q33 0 56.5 23.5T760-680v400q0 33-23.5 56.5T680-20" +
            "0h-80Zm-320 0q-33 0-56.5-23.5T200-280v-400q0-33 23.5-56.5T280-760h80q33 0 56.5 23.5T440-680v400q0 33-23.5 56.5T3" +
            "60-200h-80Zm320-80h80v-400h-80v400Zm-320 0h80v-400h-80v400Zm0-400v400-400Zm320 0v400-400Z",
        )
    }

    /** Material Symbols Rounded person, outlined. */
    val Person: ImageVector by lazy {
        symbol(
            "Person",
            "M480-480q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47ZM160-240v-32q0-34 17.5-6" +
            "2.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v32q0 33-23.5 56.5T720-160H240q-" +
            "33 0-56.5-23.5T160-240Zm80 0h480v-32q0-11-5.5-20T700-306q-54-27-109-40.5T480-360q-56 0-111 13.5T260-306q-9 5-14." +
            "5 14t-5.5 20v32Zm240-320q33 0 56.5-23.5T560-640q0-33-23.5-56.5T480-720q-33 0-56.5 23.5T400-640q0 33 23.5 56.5T48" +
            "0-560Zm0-80Zm0 400Z",
        )
    }

    /** Material Symbols Rounded phone_android, outlined. */
    val PhoneAndroid: ImageVector by lazy {
        symbol(
            "PhoneAndroid",
            "M420-160h120q8 0 14-6t6-14q0-8-6-14t-14-6H420q-8 0-14 6t-6 14q0 8 6 14t14 6ZM280-40q-33 0-56.5-23.5T200-120v-720" +
            "q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v720q0 33-23.5 56.5T680-40H280Zm0-200v120h400v-120H280Zm0-80h4" +
            "00v-400H280v400Zm0-480h400v-40H280v40Zm0 560v120-120Zm0-560v-40 40Z",
        )
    }

    /** Material Symbols Rounded photo_album, outlined. */
    val PhotoAlbum: ImageVector by lazy {
        symbol(
            "PhotoAlbum",
            "M240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h480q33 0 56.5 23.5T800-800v640q0 33-23.5 56.5T720-80" +
            "H240Zm0-80h480v-640h-80v245q0 12-10 17.5t-20-.5l-49-30q-10-6-20.5-6t-20.5 6l-49 30q-10 6-20.5.5T440-555v-245H240" +
            "v640Zm200-120-49-66q-6-8-16-8t-16 8l-55 74q-8 10-2 21t18 11h320q12 0 18-11t-2-21l-95-127q-6-8-16-8t-16 8l-89 119" +
            "ZM240-160v-640 640Zm200-395q0 12 10.5 17.5t20.5-.5l49-30q10-6 20.5-6t20.5 6l49 30q10 6 20 .5t10-17.5q0 12-10 17." +
            "5t-20-.5l-49-30q-10-6-20.5-6t-20.5 6l-49 30q-10 6-20.5.5T440-555Z",
        )
    }

    /** Material Symbols Rounded photo_album, filled. */
    val PhotoAlbumFilled: ImageVector by lazy {
        symbol(
            "PhotoAlbumFilled",
            "M240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h480q33 0 56.5 23.5T800-800v640q0 33-23.5 56.5T720-80" +
            "H240Zm200-200-49-65q-6-8-16.5-8t-16.5 8l-55 74q-7 10-1.5 20.5T319-240h321q12 0 17.5-10.5T656-272l-95-126q-6-8-16" +
            "-8t-16 8l-89 118Zm0-520v245q0 12 9.5 17.5t20.5-.5l49-29q10-6 20.5-6t20.5 6l49 29q11 6 21 .5t10-17.5v-245H440Z",
        )
    }

    /** Material Symbols Rounded photo_library, outlined. */
    val PhotoLibrary: ImageVector by lazy {
        symbol(
            "PhotoLibrary",
            "m530-460-46-60q-6-8-16-8t-16 8l-67 88q-8 10-2.5 21t18.5 11h318q13 0 18.5-11t-2.5-21l-97-127q-6-8-16-8t-16 8l-76 " +
            "99ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T80" +
            "0-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-520q0-17 11.5-28.5T120-720q17 0 28.5 11.5T160-6" +
            "80v520h520q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H160Zm160-720v480-480Z",
        )
    }

    /** Material Symbols Rounded photo_library, filled. */
    val PhotoLibraryFilled: ImageVector by lazy {
        symbol(
            "PhotoLibraryFilled",
            "M360-400h400L622-580l-92 120-62-80-108 140Zm-40 160q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0" +
            " 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-560h80v" +
            "560h560v80H160Zm160-720h480v480H320v-480Z",
        )
    }

    /** Material Symbols Rounded location_on, outlined. */
    val Place: ImageVector by lazy {
        symbol(
            "Place",
            "M480-186q122-112 181-203.5T720-552q0-109-69.5-178.5T480-800q-101 0-170.5 69.5T240-552q0 71 59 162.5T480-186Zm0 7" +
            "9q-14 0-28-5t-25-15q-65-60-115-117t-83.5-110.5q-33.5-53.5-51-103T160-552q0-150 96.5-239T480-880q127 0 223.5 89T8" +
            "00-552q0 45-17.5 94.5t-51 103Q698-301 648-244T533-127q-11 10-25 15t-28 5Zm0-453Zm0 80q33 0 56.5-23.5T560-560q0-3" +
            "3-23.5-56.5T480-640q-33 0-56.5 23.5T400-560q0 33 23.5 56.5T480-480Z",
        )
    }

    /** Material Symbols Rounded play_arrow, filled. */
    val PlayArrow: ImageVector by lazy {
        symbol(
            "PlayArrow",
            "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-" +
            "5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z",
        )
    }

    /** Material Symbols Rounded print, outlined. */
    val Print: ImageVector by lazy {
        symbol(
            "Print",
            "M320-120q-33 0-56.5-23.5T240-200v-80h-80q-33 0-56.5-23.5T80-360v-160q0-51 35-85.5t85-34.5h560q51 0 85.5 34.5T880" +
            "-520v160q0 33-23.5 56.5T800-280h-80v80q0 33-23.5 56.5T640-120H320ZM160-360h80q0-33 23.5-56.5T320-440h320q33 0 56" +
            ".5 23.5T720-360h80v-160q0-17-11.5-28.5T760-560H200q-17 0-28.5 11.5T160-520v160Zm480-280v-120H320v120h-80v-120q0-" +
            "33 23.5-56.5T320-840h320q33 0 56.5 23.5T720-760v120h-80Zm80 180q17 0 28.5-11.5T760-500q0-17-11.5-28.5T720-540q-1" +
            "7 0-28.5 11.5T680-500q0 17 11.5 28.5T720-460Zm-80 260v-160H320v160h320ZM160-560h640-640Z",
        )
    }

    /** Material Symbols Rounded refresh, outlined. */
    val Refresh: ImageVector by lazy {
        symbol(
            "Refresh",
            "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-70q0-17 11.5-28.5T760-800q17 0 28.5 11" +
            ".5T800-760v200q0 17-11.5 28.5T760-520H560q-17 0-28.5-11.5T520-560q0-17 11.5-28.5T560-600h128q-32-56-87.5-88T480-" +
            "720q-100 0-170 70t-70 170q0 100 70 170t170 70q68 0 124.5-34.5T692-367q8-14 22.5-19.5t29.5-.5q16 5 23 21t-1 30q-4" +
            "1 80-117 128t-169 48Z",
        )
    }

    /** Material Symbols Rounded download, outlined. */
    val SaveAlt: ImageVector by lazy {
        symbol(
            "SaveAlt",
            "M480-337q-8 0-15-2.5t-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T48" +
            "0-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5" +
            "ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T76" +
            "0-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z",
        )
    }

    /** Material Symbols Rounded search, outlined. */
    val Search: ImageVector by lazy {
        symbol(
            "Search",
            "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q1" +
            "1 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T" +
            "380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z",
        )
    }

    /** Material Symbols Rounded share, outlined. */
    val Share: ImageVector by lazy {
        symbol(
            "Share",
            "M720-80q-50 0-85-35t-35-85q0-7 1-14.5t3-13.5L322-392q-17 15-38 23.5t-44 8.5q-50 0-85-35t-35-85q0-50 35-85t85-35q" +
            "23 0 44 8.5t38 23.5l282-164q-2-6-3-13.5t-1-14.5q0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-23 0-44-8.5" +
            "T638-672L356-508q2 6 3 13.5t1 14.5q0 7-1 14.5t-3 13.5l282 164q17-15 38-23.5t44-8.5q50 0 85 35t35 85q0 50-35 85t-" +
            "85 35Zm0-640q17 0 28.5-11.5T760-760q0-17-11.5-28.5T720-800q-17 0-28.5 11.5T680-760q0 17 11.5 28.5T720-720ZM240-4" +
            "40q17 0 28.5-11.5T280-480q0-17-11.5-28.5T240-520q-17 0-28.5 11.5T200-480q0 17 11.5 28.5T240-440Zm480 280q17 0 28" +
            ".5-11.5T760-200q0-17-11.5-28.5T720-240q-17 0-28.5 11.5T680-200q0 17 11.5 28.5T720-160Zm0-600ZM240-480Zm480 280Z",
        )
    }

    /** Material Symbols Rounded swap_vert, outlined. */
    val SwapVert: ImageVector by lazy {
        symbol(
            "SwapVert",
            "M360-440q-17 0-28.5-11.5T320-480v-247l-75 75q-11 11-27.5 11T189-652q-12-12-12-28.5t12-28.5l143-143q6-6 13-8.5t15" +
            "-2.5q8 0 15 2.5t13 8.5l144 144q12 12 11.5 28T531-652q-12 11-28 11.5T475-652l-75-75v247q0 17-11.5 28.5T360-440ZM6" +
            "00-97q-8 0-15-2.5t-13-8.5L428-252q-12-12-11.5-28t12.5-28q12-11 28-11.5t28 11.5l75 75v-247q0-17 11.5-28.5T600-520" +
            "q17 0 28.5 11.5T640-480v247l75-75q11-11 27.5-11t28.5 11q12 12 12 28.5T771-251L628-108q-6 6-13 8.5T600-97Z",
        )
    }

    /** Material Symbols Rounded visibility, outlined. */
    val Visibility: ImageVector by lazy {
        symbol(
            "Visibility",
            "M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500q0 75 52.5 127.5T480-320Zm0-72q" +
            "-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608q45 0 76.5 31.5T588-500q0 45-31.5 76.5T480-392Zm0 192q-134 0-244.5" +
            "-72T61-462q-5-9-7.5-18.5T51-500q0-10 2.5-19.5T61-538q64-118 174.5-190T480-800q134 0 244.5 72T899-538q5 9 7.5 18." +
            "5T909-500q0 10-2.5 19.5T899-462q-64 118-174.5 190T480-200Zm0-300Zm0 220q113 0 207.5-59.5T832-500q-50-101-144.5-1" +
            "60.5T480-720q-113 0-207.5 59.5T128-500q50 101 144.5 160.5T480-280Z",
        )
    }

    /** Material Symbols Rounded visibility_off, outlined. */
    val VisibilityOff: ImageVector by lazy {
        symbol(
            "VisibilityOff",
            "M607-627q29 29 42.5 66t9.5 76q0 15-11 25.5T622-449q-15 0-25.5-10.5T586-485q5-26-3-50t-25-41q-17-17-41-26t-51-4q-" +
            "15 0-25.5-11T430-643q0-15 10.5-25.5T466-679q38-4 75 9.5t66 42.5Zm-127-93q-19 0-37 1.5t-36 5.5q-17 3-30.5-5T358-7" +
            "42q-5-16 3.5-31t24.5-18q23-5 46.5-7t47.5-2q137 0 250.5 72T904-534q4 8 6 16.5t2 17.5q0 9-1.5 17.5T905-466q-18 40-" +
            "44.5 75T802-327q-12 11-28 9t-26-16q-10-14-8.5-30.5T753-392q24-23 44-50t35-58q-50-101-144.5-160.5T480-720Zm0 520q" +
            "-134 0-245-72.5T60-463q-5-8-7.5-17.5T50-500q0-10 2-19t7-18q20-40 46.5-76.5T166-680l-83-84q-11-12-10.5-28.5T84-82" +
            "0q11-11 28-11t28 11l680 680q11 11 11.5 27.5T820-84q-11 11-28 11t-28-11L624-222q-35 11-71 16.5t-73 5.5ZM222-624q-" +
            "29 26-53 57t-41 67q50 101 144.5 160.5T480-280q20 0 39-2.5t39-5.5l-36-38q-11 3-21 4.5t-21 1.5q-75 0-127.5-52.5T30" +
            "0-500q0-11 1.5-21t4.5-21l-84-82Zm319 93Zm-151 75Z",
        )
    }

    /** Material Symbols Rounded volume_off, outlined. */
    val VolumeOff: ImageVector by lazy {
        symbol(
            "VolumeOff",
            "M671-177q-11 7-22 13t-23 11q-15 7-30.5 0T574-176q-6-15 1.5-29.5T598-227q7-3 13-6.5t12-7.5L480-368v111q0 27-24.5 " +
            "37.5T412-228L280-360H160q-17 0-28.5-11.5T120-400v-160q0-17 11.5-28.5T160-600h88L84-764q-11-11-11-28t11-28q11-11 " +
            "28-11t28 11l680 680q11 11 11 28t-11 28q-11 11-28 11t-28-11l-93-93Zm89-304q0-83-44-151.5T598-735q-15-7-22-21.5t-2" +
            "-29.5q6-16 21.5-23t31.5 0q97 43 155 131t58 197q0 33-6 65.5T817-353q-8 22-24.5 27.5t-30.5.5q-14-5-22.5-18t-.5-30q" +
            "11-26 16-52.5t5-55.5ZM591-623q33 21 51 63t18 80v10q0 5-1 10-2 13-14 17t-22-6l-51-51q-6-6-9-13.5t-3-15.5v-77q0-12" +
            " 10.5-17.5t20.5.5Zm-201-59q-6-6-6-14t6-14l22-22q19-19 43.5-8.5T480-703v63q0 14-12 19t-22-5l-56-56Zm10 328v-94l-7" +
            "2-72H200v80h114l86 86Zm-36-130Z",
            autoMirror = true,
        )
    }

    /** Material Symbols Rounded volume_up, outlined. */
    val VolumeUp: ImageVector by lazy {
        symbol(
            "VolumeUp",
            "M760-481q0-83-44-151.5T598-735q-15-7-22-21.5t-2-29.5q6-16 21.5-23t31.5 0q97 43 155 131.5T840-481q0 108-58 196.5T" +
            "627-153q-16 7-31.5 0T574-176q-5-15 2-29.5t22-21.5q74-34 118-102.5T760-481ZM280-360H160q-17 0-28.5-11.5T120-400v-" +
            "160q0-17 11.5-28.5T160-600h120l132-132q19-19 43.5-8.5T480-703v446q0 27-24.5 37.5T412-228L280-360Zm380-120q0 42-1" +
            "9 79.5T591-339q-10 6-20.5.5T560-356v-250q0-12 10.5-17.5t20.5.5q31 25 50 63t19 80ZM400-606l-86 86H200v80h114l86 8" +
            "6v-252ZM300-480Z",
            autoMirror = true,
        )
    }
}

/** A Material Symbols glyph from its SVG path data, drawn in the symbols' 960-unit viewport (y from -960 to 0). */
private fun symbol(name: String, pathData: String, autoMirror: Boolean = false): ImageVector = ImageVector.Builder(
    name = "Alba.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f,
    autoMirror = autoMirror,
).addGroup(translationY = 960f).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).clearGroup().build()

/** Favourites are a heart everywhere: filled in the favourite red when set, outlined in the content colour when not. */
@Composable
internal fun FavoriteIcon(favorite: Boolean, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = if (favorite) AlbaTheme.colors.favorite else LocalContentColor.current) {
    Icon(if (favorite) AlbaIcons.Favorite else AlbaIcons.FavoriteBorder, contentDescription, modifier, tint)
}

/** An iCloud collection or media-type glyph, in the primary colour (at least 3:1 on every surface). */
@Composable
internal fun ICloudIcon(@DrawableRes resource: Int) {
    Icon(painterResource(resource), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(AlbaIconSize.standard))
}
