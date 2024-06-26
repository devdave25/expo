package expo.modules.contacts

import android.content.ContentValues
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract.Groups
import java.text.ParseException

class Group(var groupId: String) {
  var groupName: String? = null

  fun fromCursor(cursor: Cursor) {
    groupName = cursor.getString(cursor.getColumnIndexOrThrow(Groups.TITLE))
  }

  // convert to react native object
  @Throws(ParseException::class)
  fun toMap(): Bundle {
    val group = Bundle().apply {
      putString("groupId", groupId)

      groupName
        ?.takeIf(String::isNotEmpty)
        ?.let { putString("groupName", it) }
    }

    return group
  }

  val contentValues: ArrayList<ContentValues>
    get() {
      val groupData = ArrayList<ContentValues>()
      val groupValues = ContentValues().apply {
        put(Groups.TITLE, groupName)
      }
      groupData.add(groupValues)

      return groupData
    }
}
