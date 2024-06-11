package expo.modules.contacts

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import expo.modules.contacts.models.BaseModel
import expo.modules.contacts.models.DateModel
import expo.modules.contacts.models.EmailModel
import expo.modules.contacts.models.ExtraNameModel
import expo.modules.contacts.models.ImAddressModel
import expo.modules.contacts.models.PhoneNumberModel
import expo.modules.contacts.models.PostalAddressModel
import expo.modules.contacts.models.RelationshipModel
import expo.modules.contacts.models.UrlAddressModel
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.launch
import java.util.UUID

data class ContactPage(
  val data: List<Contact>,
  val hasPreviousPage: Boolean = false,
  val hasNextPage: Boolean = false,
  val total: Int = data.size
)

fun ContactPage?.toBundle(keys: Set<String>): Bundle {
  val data = this?.data?.map { it.toMap(keys) } ?: emptyList()
  val hasNextPage = this?.hasNextPage ?: false
  val hasPreviousPage = this?.hasPreviousPage ?: false

  return Bundle().apply {
    putParcelableArrayList("data", ArrayList(data))
    putBoolean("hasNextPage", hasNextPage)
    putBoolean("hasPreviousPage", hasPreviousPage)
  }
}

fun Contact?.toBundle(keys: Set<String>): Bundle {
  val serializedContact = this?.toMap(keys)
  val data = serializedContact?.let { listOf(it) } ?: emptyList()

  return Bundle().apply {
    putParcelableArrayList("data", ArrayList(data))
    putBoolean("hasNextPage", false)
    putBoolean("hasPreviousPage", false)
  }
}

fun List<Group>?.toBundle(): Bundle {
  val keys = setOf("groupId", "groupName")
  val data = this?.map { it.toMap(keys) } ?: emptyList()

  return Bundle().apply {
    putParcelableArrayList("data", ArrayList(data))
  }
}

fun Group?.toBundle(): Bundle {
  val keys = setOf("groupId", "groupName")
  val serializedGroup = this?.toMap(keys)
  val data = serializedGroup?.let { listOf(it) } ?: emptyList()

  return Bundle().apply {
    putParcelableArrayList("data", ArrayList(data))
  }
}

private val defaultFields = setOf(
  "phoneNumbers", "emails", "addresses", "note", "birthday", "dates", "instantMessageAddresses",
  "urlAddresses", "extraNames", "relationships", "phoneticFirstName", "phoneticLastName", "phoneticMiddleName",
  "namePrefix", "nameSuffix", "name", "firstName", "middleName", "lastName", "nickname", "id", "jobTitle",
  "company", "department", "image", "imageAvailable", "note"
)

const val RC_EDIT_CONTACT = 2137
const val RC_PICK_CONTACT = 2138
const val RC_ADD_CONTACT = 2139

// TODO: Evan: default API is confusing. Duplicate data being requested.
private val DEFAULT_PROJECTION = listOf(
  ContactsContract.Data.RAW_CONTACT_ID,
  ContactsContract.Data.CONTACT_ID,
  ContactsContract.Data.LOOKUP_KEY,
  ContactsContract.Contacts.Data.MIMETYPE,
  ContactsContract.Profile.DISPLAY_NAME,
  CommonDataKinds.Contactables.PHOTO_URI,
  CommonDataKinds.Contactables.PHOTO_THUMBNAIL_URI,
  CommonDataKinds.StructuredName.DISPLAY_NAME,
  CommonDataKinds.StructuredName.GIVEN_NAME,
  CommonDataKinds.StructuredName.MIDDLE_NAME,
  CommonDataKinds.StructuredName.FAMILY_NAME,
  CommonDataKinds.StructuredName.PREFIX,
  CommonDataKinds.StructuredName.SUFFIX,
  CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME,
  CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME,
  CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME,
  CommonDataKinds.Organization.COMPANY,
  CommonDataKinds.Organization.TITLE,
  CommonDataKinds.Organization.DEPARTMENT
)

class ContactQuery : Record {
  @Field
  val pageSize = 0

  @Field
  val pageOffset = 0

  @Field
  val fields: Set<String> = defaultFields

  @Field
  val sort: String? = null

  @Field
  val name: String? = null

  @Field
  val groupId: String? = null

  @Field
  val id: String? = null
}

class GroupQuery : Record {
  @Field
  val groupName: String? = null

  @Field
  val groupId: String? = null
}

class QueryArguments(
  val projection: Array<String>,
  val selection: String,
  val selectionArgs: Array<String>
)

class ContactsModule : Module() {
  private var contactPickingPromise: Promise? = null
  private var contactManipulationPromise: Promise? = null

  private val permissionsManager: Permissions
    get() = appContext.permissions ?: throw Exceptions.PermissionsModuleNotFound()

  private val activity: Activity
    get() = appContext.activityProvider?.currentActivity ?: throw Exceptions.MissingActivity()

  override fun definition() = ModuleDefinition {
    Name("ExpoContacts")

    AsyncFunction("requestPermissionsAsync") { promise: Promise ->
      if (permissionsManager.isPermissionPresentInManifest(Manifest.permission.WRITE_CONTACTS)) {
        Permissions.askForPermissionsWithPermissionsManager(permissionsManager, promise, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
      } else {
        Permissions.askForPermissionsWithPermissionsManager(permissionsManager, promise, Manifest.permission.READ_CONTACTS)
      }
    }

    AsyncFunction("getPermissionsAsync") { promise: Promise ->
      if (permissionsManager.isPermissionPresentInManifest(Manifest.permission.WRITE_CONTACTS)) {
        Permissions.getPermissionsWithPermissionsManager(permissionsManager, promise, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
      } else {
        Permissions.getPermissionsWithPermissionsManager(permissionsManager, promise, Manifest.permission.READ_CONTACTS)
      }
    }

    AsyncFunction("getContactsAsync") { options: ContactQuery, promise: Promise ->
      ensureReadPermission()

      appContext
        .backgroundCoroutineScope
        .launch {
          if (options.id != null) {
            val contact = getContactById(options.id, options.fields)
            promise.resolve(contact.toBundle(options.fields))
            return@launch
          }

          val name = options.name
          val group = options.groupId
          val contactData = if (!name.isNullOrBlank()) {
            val predicateMatchingName = "%$name%"
            getContactsByName(predicateMatchingName, options.fields, options.sort)
          } else if (!group.isNullOrBlank()) {
            getContactsByGroup(group, options.fields, options.sort)
          } else {
            getAllContactsAsync(options)
          }

          promise.resolve(contactData.toBundle(options.fields))
        }
    }

    AsyncFunction("addContactAsync") { data: Map<String, Any>, _: String? ->
      ensurePermissions()

      val contact = mutateContact(null, data)
      val ops = contact.toInsertOperationList()
      val result = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
      if (result.isNotEmpty()) {
        resolver.query(
          result[0].uri!!,
          arrayOf(ContactsContract.RawContacts.CONTACT_ID),
          null,
          null,
          null
        ).use { cursor ->
          if (cursor == null) {
            throw RetrieveIdException()
          }
          cursor.moveToNext()
          return@AsyncFunction cursor.getLong(0).toString()
        }
      } else {
        throw AddContactException()
      }
    }

    AsyncFunction("updateContactAsync") { contact: Map<String, Any> ->
      ensurePermissions()

      val id = if (contact.containsKey("id")) contact["id"] as String? else null
      var targetContact = getContactById(id, defaultFields)

      if (targetContact != null) {
        targetContact = mutateContact(targetContact, contact)
        val ops = targetContact.toUpdateOperationList()

        val result = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        if (result.isNotEmpty()) {
          id
        } else {
          throw ContactUpdateException()
        }
      } else {
        throw ContactNotFoundException()
      }
    }

    AsyncFunction("removeContactAsync") { contactId: String? ->
      ensurePermissions()

      val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId)
      resolver.delete(uri, null, null)
    }

    AsyncFunction("getGroupsAsync") { options: GroupQuery, promise: Promise ->
      ensureReadPermission()

      appContext
        .backgroundCoroutineScope
        .launch {
          if (options.groupId != null) {
            val group = getGroupById(options.groupId)
            promise.resolve(group.toBundle())
            return@launch
          }

          val name = options.groupName
          val groupData = if (!name.isNullOrBlank()) {
            val predicateMatchingName = "%$name%"
            getGroupByName(predicateMatchingName)
          } else {
            fetchGroups(null, null)
          }

          promise.resolve(groupData.toBundle())
        }
    }

    AsyncFunction("createGroupAsync") { name: String? ->
      ensurePermissions()

      val resolver: ContentResolver = context.contentResolver
      val contentValues = ContentValues().apply {
        put(ContactsContract.Groups.TITLE, name)
      }

      val uri: Uri = resolver.insert(ContactsContract.Groups.CONTENT_URI, contentValues)
      val groupId = uri.lastPathSegment?.toLongOrNull()
    }

    AsyncFunction("updateGroupName") { groupName: String?, groupId: String? ->
      ensurePermissions()

      var targetGroup = getGroupById(groupId)

      if (targetGroup != null) {
        val resolver: ContentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
          put(ContactsContract.Groups.TITLE, groupName)
        }

        val uri: Uri = Uri.withAppendedPath(ContactsContract.Groups.CONTENT_URI, groupId)
        resolver.update(uri, contentValues, null, null)
      } else {
        throw GroupNotFoundException()
      }
    }

    AsyncFunction("removeGroupAsync") { groupId: String? ->
      ensurePermissions()

      val uri = Uri.withAppendedPath(ContactsContract.Groups.CONTENT_URI, groupId)
      val rowsDeleted = resolver.delete(uri, null, null)
      return rowsDeleted
    }

    AsyncFunction("addExistingContactToGroupAsync") { contactId: String?, groupId: String? ->
      ensurePermissions()

      var targetGroup = getGroupById(groupId)

      if (targetGroup != null) {
        val contentValues = ContentValues().apply {
          put(ContactsContract.CommonDataKinds.GroupMembership.RAW_CONTACT_ID, contactId)
          put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
          put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
        }
        val uri: Uri = ContactsContract.Data.CONTENT_URI
        resolver.insert(uri, contentValues)
      } else {
        throw GroupNotFoundException()
      }
    }

    AsyncFunction("removeContactFromGroupAsync") { contactId: String?, groupId: String? ->
      ensurePermissions()

      var targetGroup = getGroupById(groupId)

      if (targetGroup != null) {
        val selection = "${ContactsContract.CommonDataKinds.GroupMembership.RAW_CONTACT_ID} = ? AND " +
                "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?"
        val selectionArgs = arrayOf(contactId, groupId)
        val uri: Uri = ContactsContract.Data.CONTENT_URI
        val rowsDeleted = resolver.delete(uri, selection, selectionArgs)
        return rowsDeleted
      } else {
        throw GroupNotFoundException()
      }
    }

    AsyncFunction("shareContactAsync") { contactId: String?, subject: String? ->
      val lookupKey = getLookupKeyForContactId(contactId) ?: throw LookupKeyNotFoundException()

      val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
      }
      activity.startActivity(intent)
    }

    AsyncFunction("writeContactToFileAsync") { contact: Map<String, Any?> ->
      ensureReadPermission()
      val id = if (contact.containsKey("id")) contact["id"] as String? else null
      val lookupKey = getLookupKeyForContactId(id) ?: throw LookupKeyNotFoundException()
      val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
      uri.toString()
    }

    AsyncFunction("presentFormAsync") { contactId: String?, contactData: Map<String, Any>?, _: Map<String, Any?>?, promise: Promise ->
      ensureReadPermission()

      if (contactManipulationPromise != null) {
        throw ContactManipulationInProgressException()
      }

      if (contactId != null) {
        val contact = getContactById(contactId, defaultFields) ?: throw ContactNotFoundException()
        presentEditForm(contact, promise)
      }
      // Create contact from supplied data.
      if (contactData != null) {
        val contact = mutateContact(null, contactData)
        presentForm(contact, promise)
      }
    }

    OnActivityResult { _, payload ->
      val (requestCode, resultCode, intent) = payload
      if (requestCode == RC_EDIT_CONTACT || requestCode == RC_ADD_CONTACT) {
        val pendingPromise = contactManipulationPromise ?: return@OnActivityResult

        pendingPromise.resolve(0)

        contactManipulationPromise = null
      }
      if (requestCode == RC_PICK_CONTACT) {
        val pendingPromise = contactPickingPromise ?: return@OnActivityResult

        if (resultCode == Activity.RESULT_OK) {
          val contactId = intent?.data?.lastPathSegment
          val contact = getContactById(contactId, defaultFields)
          pendingPromise.resolve(contact?.toMap(defaultFields))
        } else {
          pendingPromise.resolve()
        }

        contactPickingPromise = null
      }
    }

    AsyncFunction("presentContactPickerAsync") { promise: Promise ->
      if (contactPickingPromise != null) {
        throw ContactPickingInProgressException()
      }

      val intent = Intent(Intent.ACTION_PICK)
      intent.setType(ContactsContract.Contacts.CONTENT_TYPE)

      contactPickingPromise = promise
      activity.startActivityForResult(intent, RC_PICK_CONTACT)
    }
  }

  private fun presentForm(contact: Contact, promise: Promise) {
    val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
    intent.putExtra(ContactsContract.Intents.Insert.NAME, contact.getFinalDisplayName())
    intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, contact.contentValues)
    contactManipulationPromise = promise
    activity.startActivityForResult(intent, RC_ADD_CONTACT)
  }

  private fun presentEditForm(contact: Contact, promise: Promise) {
    val selectedContactUri = ContactsContract.Contacts.getLookupUri(
      contact.contactId.toLong(),
      contact.lookupKey
    )
    val intent = Intent(Intent.ACTION_EDIT)
    intent.setDataAndType(selectedContactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
    contactManipulationPromise = promise
    activity.startActivityForResult(intent, RC_EDIT_CONTACT)
  }

  private val resolver: ContentResolver
    get() = (appContext.reactContext ?: throw Exceptions.ReactContextLost()).contentResolver

  private fun mutateContact(initContact: Contact?, data: Map<String, Any>): Contact {
    val contact = initContact ?: Contact(UUID.randomUUID().toString())

    data.safeGet<String>("firstName")?.let { contact.firstName = it }
    data.safeGet<String>("middleName")?.let { contact.middleName = it }
    data.safeGet<String>("lastName")?.let { contact.lastName = it }
    data.safeGet<String>("namePrefix")?.let { contact.prefix = it }
    data.safeGet<String>("nameSuffix")?.let { contact.suffix = it }
    data.safeGet<String>("phoneticFirstName")?.let { contact.phoneticFirstName = it }
    data.safeGet<String>("phoneticMiddleName")?.let { contact.phoneticMiddleName = it }
    data.safeGet<String>("phoneticLastName")?.let { contact.phoneticLastName = it }
    data.safeGet<String>("company")?.let { contact.company = it }
    data.safeGet<String>("jobTitle")?.let { contact.jobTitle = it }
    data.safeGet<String>("department")?.let { contact.department = it }
    data.safeGet<String>("note")?.let { contact.note = it }

    if (data.containsKey("image")) {
      val image = data["image"]
      if (image is String) {
        contact.photoUri = image
        contact.hasPhoto = true
      } else if (image is Map<*, *> && image.containsKey("uri")) {
        contact.photoUri = image["uri"] as String?
        contact.hasPhoto = true
      }
    }

    BaseModel.decodeList(
      data.safeGet("addresses"),
      PostalAddressModel::class.java
    )?.let {
      contact.addresses = it
    }

    BaseModel.decodeList(
      data.safeGet("phoneNumbers"),
      PhoneNumberModel::class.java
    )?.let {
      contact.phones = it
    }

    BaseModel.decodeList(
      data.safeGet("emails"),
      EmailModel::class.java
    )?.let {
      contact.emails = it
    }

    BaseModel.decodeList(
      data.safeGet("instantMessageAddresses"),
      ImAddressModel::class.java
    )?.let {
      contact.imAddresses = it
    }

    BaseModel.decodeList(
      data.safeGet("urlAddresses"),
      UrlAddressModel::class.java
    )?.let {
      contact.urlAddresses = it
    }

    BaseModel.decodeList(
      data.safeGet("extraNames"),
      ExtraNameModel::class.java
    )?.let {
      contact.extraNames = it
    }

    BaseModel.decodeList(
      data.safeGet("dates"),
      DateModel::class.java
    )?.let {
      contact.dates = it
    }

    BaseModel.decodeList(
      data.safeGet("relationships"),
      RelationshipModel::class.java
    )?.let {
      contact.relationships = it
    }

    return contact
  }

  private fun getLookupKeyForContactId(contactId: String?): String? {
    return resolver.query(
      ContactsContract.Contacts.CONTENT_URI,
      arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
      ContactsContract.Contacts._ID + " = " + contactId,
      null,
      null
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        cursor.getString(0)
      } else {
        null
      }
    }
  }

  private fun getContactById(contactId: String?, keysToFetch: Set<String>): Contact? {
    val queryArguments = createProjectionForQuery(keysToFetch)
    val cursorSelection = ContactsContract.Data.CONTACT_ID + " = ?"
    resolver.query(
      ContactsContract.Data.CONTENT_URI,
      queryArguments.projection,
      cursorSelection,
      arrayOf(contactId),
      null
    )?.use { cursor ->
      val contacts = loadContactsFrom(cursor)
      val contactList = ArrayList(contacts.values)
      if (contactList.size > 0) {
        return contactList[0]
      }
    }
    return null
  }

  private fun getContactsByGroup(query: String, keysToFetch: Set<String>, sortOrder: String?): ContactPage? {
    return fetchContacts(
      0,
      9999,
      arrayOf(query),
      ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
      keysToFetch,
      sortOrder
    )
  }

  private fun getContactsByName(query: String, keysToFetch: Set<String>, sortOrder: String?): ContactPage? {
    return fetchContacts(
      0,
      9999,
      arrayOf(query),
      ContactsContract.Data.DISPLAY_NAME_PRIMARY,
      keysToFetch,
      sortOrder
    )
  }

  private fun getGroupById(groupId: String?): Group? {
    val cursorSelection = ContactsContract.Groups._ID + " = ?"
    resolver.query(
      ContactsContract.Groups.CONTENT_URI,
      arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
      cursorSelection,
      arrayOf(groupId),
      null
    )?.use { cursor ->
      val groups = loadGroupsFrom(cursor)
      val groupList = ArrayList(groups.values)
      if (groupList.size > 0) {
        return groupList[0]
      }
    }
    return null
  }

  private fun getGroupByName(query: String): List<Group>? {
    return fetchGroups(
      arrayOf(query),
      ContactsContract.Groups.TITLE
    )
  }

  private fun getAllContactsAsync(options: ContactQuery): ContactPage? {
    return fetchContacts(
      options.pageOffset,
      options.pageSize,
      null,
      null,
      options.fields,
      options.sort
    )
  }

  private fun createProjectionForQuery(keysToFetch: Set<String>): QueryArguments {
    val projection: MutableList<String> = ArrayList(DEFAULT_PROJECTION)
    val selectionArgs = ArrayList(
      listOf(
        CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        CommonDataKinds.Organization.CONTENT_ITEM_TYPE
      )
    )

    // selection ORs need to match arg count from above selectionArgs
    var selection = Columns.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=?"

    // handle "add on" fields from query request
    if (keysToFetch.contains("phoneNumbers")) {
      projection.add(CommonDataKinds.Phone.NUMBER)
      projection.add(CommonDataKinds.Phone.TYPE)
      projection.add(CommonDataKinds.Phone.LABEL)
      projection.add(CommonDataKinds.Phone.IS_PRIMARY)
      projection.add(CommonDataKinds.Phone._ID)
      selection += " OR " + Columns.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("emails")) {
      projection.add(CommonDataKinds.Email.DATA)
      projection.add(CommonDataKinds.Email.ADDRESS)
      projection.add(CommonDataKinds.Email.TYPE)
      projection.add(CommonDataKinds.Email.LABEL)
      projection.add(CommonDataKinds.Email.IS_PRIMARY)
      projection.add(CommonDataKinds.Email._ID)
      selection += " OR " + Columns.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Email.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("addresses")) {
      projection.add(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
      projection.add(CommonDataKinds.StructuredPostal.TYPE)
      projection.add(CommonDataKinds.StructuredPostal.LABEL)
      projection.add(CommonDataKinds.StructuredPostal.STREET)
      projection.add(CommonDataKinds.StructuredPostal.POBOX)
      projection.add(CommonDataKinds.StructuredPostal.NEIGHBORHOOD)
      projection.add(CommonDataKinds.StructuredPostal.CITY)
      projection.add(CommonDataKinds.StructuredPostal.REGION)
      projection.add(CommonDataKinds.StructuredPostal.POSTCODE)
      projection.add(CommonDataKinds.StructuredPostal.COUNTRY)
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("note")) {
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Note.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("birthday") || keysToFetch.contains("dates")) {
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Event.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("instantMessageAddresses")) {
      projection.add(CommonDataKinds.Im.DATA)
      projection.add(CommonDataKinds.Im.TYPE)
      projection.add(CommonDataKinds.Im.PROTOCOL)
      projection.add(CommonDataKinds.Im._ID)
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Im.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("urlAddresses")) {
      projection.add(CommonDataKinds.Website.URL)
      projection.add(CommonDataKinds.Website.TYPE)
      projection.add(CommonDataKinds.Website._ID)
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Website.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("extraNames")) {
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("relationships")) {
      projection.add(CommonDataKinds.Relation.NAME)
      projection.add(CommonDataKinds.Relation.TYPE)
      projection.add(CommonDataKinds.Relation._ID)
      selection += " OR " + ContactsContract.Data.MIMETYPE + "=?"
      selectionArgs.add(CommonDataKinds.Relation.CONTENT_ITEM_TYPE)
    }
    if (keysToFetch.contains("phoneticFirstName")) projection.add(CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME)
    if (keysToFetch.contains("phoneticLastName")) projection.add(CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME)
    if (keysToFetch.contains("phoneticMiddleName")) projection.add(CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME)
    if (keysToFetch.contains("namePrefix")) projection.add(CommonDataKinds.StructuredName.PREFIX)
    if (keysToFetch.contains("nameSuffix")) projection.add(CommonDataKinds.StructuredName.SUFFIX)

    return QueryArguments(
      projection.toTypedArray(),
      selection,
      selectionArgs.toTypedArray()
    )
  }

  private fun fetchContacts(
    pageOffset: Int,
    pageSize: Int,
    queryStrings: Array<String>?,
    initQueryField: String?,
    keysToFetch: Set<String>,
    sortOrder: String?
  ): ContactPage? {
    val queryField = initQueryField ?: ContactsContract.Data.CONTACT_ID
    val getAll = pageSize == 0
    val queryArguments = createProjectionForQuery(keysToFetch)
    val contacts: Map<String, Contact>
    val cr = resolver

    if (!queryStrings.isNullOrEmpty()) {
      val cursorProjection = queryArguments.projection
      val cursorSelection = "$queryField LIKE ?"
      cr.query(
        ContactsContract.Data.CONTENT_URI,
        cursorProjection,
        cursorSelection,
        queryStrings,
        null
      )
    } else {
      cr.query(
        ContactsContract.Data.CONTENT_URI,
        queryArguments.projection,
        queryArguments.selection,
        queryArguments.selectionArgs,
        null
      )
    }?.use { cursor ->
      contacts = loadContactsFrom(cursor)
      val contactsArray = ArrayList<Contact>()

      // introduce paging at this level to ensure all data elements
      // are appropriately mapped to contacts from cursor
      // NOTE: paging performance improvement is minimized as cursor iterations will
      // always fully run
      var contactList = ArrayList<Contact>(contacts.values)
      contactList = sortContactsBy(contactList, sortOrder)
      val contactListSize = contactList.size

      // convert from contact pojo to react native
      var currentIndex = if (getAll) {
        0
      } else {
        pageOffset
      }
      while (currentIndex < contactListSize) {
        val contact = contactList[currentIndex]

        // if fetching single contact, short circuit and return contact
        if (!getAll && currentIndex - pageOffset >= pageSize) {
          break
        }
        contactsArray.add(contact)
        currentIndex++
      }

      return ContactPage(
        contactsArray,
        hasPreviousPage = pageOffset > 0,
        hasNextPage = pageOffset + pageSize < contactListSize,
        total = contactListSize
      )
    }
    return null
  }

  private fun sortContactsBy(input: ArrayList<Contact>, sortOrder: String?): ArrayList<Contact> {
    when (sortOrder) {
      "firstName" ->
        input.sortWith { p1, p2 -> p1.getFinalFirstName().compareTo(p2.getFinalFirstName(), ignoreCase = true) }

      "lastName" ->
        input.sortWith { p1, p2 -> p1.getFinalLastName().compareTo(p2.getFinalLastName(), ignoreCase = true) }
    }

    return input
  }

  private fun fetchGroups(
    queryStrings: Array<String>?,
    initQueryField: String?
  ): List<Group>? {
    val queryField = initQueryField ?: ContactsContract.Groups._ID
    val groups: Map<String, Group>
    val cr = resolver

    if (!queryStrings.isNullOrEmpty()) {
      val cursorSelection = "$queryField LIKE ?"
      cr.query(
        ContactsContract.Groups.CONTENT_URI,
        arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
        cursorSelection,
        queryStrings,
        null
      )
    } else {
      cr.query(
        ContactsContract.Groups.CONTENT_URI,
        arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
        null,
        null,
        null
      )
    }?.use { cursor ->
      groups = loadGroupsFrom(cursor)

      return groups.values.toList()
    }
    return null
  }

  private fun loadContactsFrom(cursor: Cursor): Map<String, Contact> {
    val map: MutableMap<String, Contact> = LinkedHashMap()
    while (cursor.moveToNext()) {
      val columnIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
      val contactId = cursor.getString(columnIndex)

      // add or update existing contact for iterating data based on contact id
      if (!map.containsKey(contactId)) {
        map[contactId] = Contact(contactId)
      }
      val contact = map[contactId]
      contact!!.fromCursor(cursor)
    }
    return map
  }

  private fun loadGroupsFrom(cursor: Cursor): Map<String, Group> {
    val map: MutableMap<String, Group> = LinkedHashMap()
    while (cursor.moveToNext()) {
      val columnIndex = cursor.getColumnIndex(ContactsContract.Groups._ID)
      val groupId = cursor.getString(columnIndex)

      // add or update existing group for iterating data based on group id
      if (!map.containsKey(groupId)) {
        map[groupId] = Group(groupId)
      }
      val group = map[groupId]
      group!!.fromCursor(cursor)
    }
    return map
  }

  private fun ensureReadPermission() {
    val hasPermission = permissionsManager.hasGrantedPermissions(Manifest.permission.READ_CONTACTS)
    if (!hasPermission) {
      throw MissingPermissionException(Manifest.permission.READ_CONTACTS)
    }
  }

  private fun ensureWritePermission() {
    val hasPermission = permissionsManager.hasGrantedPermissions(Manifest.permission.WRITE_CONTACTS)
    if (!hasPermission) {
      throw MissingPermissionException(Manifest.permission.WRITE_CONTACTS)
    }
  }

  private fun ensurePermissions() {
    ensureReadPermission()
    ensureWritePermission()
  }
}

fun <T> Map<String, Any>.safeGet(key: String): T? {
  @Suppress("UNCHECKED_CAST")
  return this[key] as? T
}
