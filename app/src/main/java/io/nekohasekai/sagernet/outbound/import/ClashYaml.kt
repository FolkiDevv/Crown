package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/** The Clash YAML document reduced to its `proxies` sequence, each entry as a [JsonObject] tree. */
object ClashYaml {

    /**
     * Parser::clash (SubscriptionParser.cpp:415-442): null when the document is not a mapping with a `proxies`
     * sequence; the entries are returned untyped, [ClashProxy] applies the desktop's field conversions. YAML
     * errors propagate as exceptions (the caller reports them like the desktop's "YAML Exception" warning).
     */
    @JvmStatic
    fun proxies(text: String): List<JsonObject>? = document(text).proxies

    /** The parsed document: [proxies] null without a `proxies` sequence; [proxyProviders] whether the mapping lists `proxy-providers`. */
    class Document(@JvmField val proxies: List<JsonObject>?, @JvmField val mapping: Boolean, @JvmField val proxyProviders: Boolean)

    /**
     * [proxies] with what Parser::clash reports about a document without proxies. YAML errors propagate as exceptions.
     * A plain mapping is read one top-level value, and one sequence item, at a time; a document with anchors, explicit
     * tags, a top-level merge key or another root is composed whole, and its root's values constructed one at a time.
     * Both give what Yaml(SafeConstructor()).load gives, except that an alias expansion past [Expansion]'s limit, an
     * alias inside its own anchor, collections nested more than [MAX_DEPTH] deep (in the text or through aliases) and,
     * in a document composed whole, a collection as a mapping key fail as YAML errors.
     */
    @JvmStatic
    fun document(text: String): Document {
        val clean = sanitize(text)
        return try {
            StreamingRead(clean, Expansion(clean.length)).read()
        } catch (e: Unsupported) {
            loadWhole(clean)
        }
    }

    // sanitizeClashYaml (SubscriptionParser.cpp:242-247): NULs and a leading BOM confuse the YAML reader.
    private fun sanitize(text: String): String {
        var s = text.replace("\u0000", "")
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s
    }

    /**
     * snakeyaml 1.30 composes and constructs a collection by recursion without a limit (1.31 added one): a few kilobytes
     * of brackets exhaust the thread's stack. Its parser reads events without recursion, so the counts in the Composer,
     * the constructor and [Expansion], and the merge chains measured by [rejectDeepMerges], stop a deep document before
     * the recursion does.
     */
    private const val MAX_DEPTH = 96

    private fun tooDeep() = YAMLException("The document is nested too deeply")

    /** Yaml(SafeConstructor()).load: the same Composer and LoaderOptions, so aliases and their limit are unchanged. */
    private fun loadWhole(text: String): Document {
        val root = DepthComposer(text).getSingleNode()
        root?.let(::rejectCollectionKeys)
        val constructor = NodeConstructor()
        val expansion = Expansion(text.length)
        if (root is MappingNode && root.tag == Tag.MAP && !root.isTwoStepsConstruction) return constructor.readRoot(root, expansion)
        return rootDocument(root?.let(constructor::constructNode), expansion)
    }

    private fun rootDocument(data: Any?, expansion: Expansion): Document {
        val root = data as? Map<*, *> ?: return Document(null, false, false)
        return Document((root["proxies"] as? List<*>)?.let(expansion::entries), true, root.containsKey("proxy-providers"))
    }

    /** The Composer with [MAX_DEPTH]: each collection it composes is one level deeper. */
    private class DepthComposer(text: String) : Composer(ParserImpl(StreamReader(text), false), Resolver(), LoaderOptions()) {
        private var level = 0

        override fun composeSequenceNode(anchor: String?): Node {
            if (++level > MAX_DEPTH) throw tooDeep()
            return super.composeSequenceNode(anchor).also { level-- }
        }

        override fun composeMappingNode(anchor: String?): Node {
            if (++level > MAX_DEPTH) throw tooDeep()
            return super.composeMappingNode(anchor).also { level-- }
        }
    }

    /**
     * A collection as a mapping key (a set's items are keys too) is hashed, and converted to its text, with its aliases
     * expanded, before any limit can apply. Clash documents key their mappings by scalars. Each node is visited once.
     */
    private fun rejectCollectionKeys(root: Node) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        val pending = ArrayList<Node>().apply { add(root) }
        val merging = ArrayList<MappingNode>()
        while (pending.isNotEmpty()) {
            val node = pending.removeAt(pending.size - 1)
            if (!seen.add(node)) continue
            if (node is SequenceNode) pending.addAll(node.value)
            if (node is MappingNode) for (tuple in node.value) {
                if (tuple.keyNode !is ScalarNode) throw YAMLException("A collection as a mapping key is not supported")
                pending.add(tuple.valueNode)
            }
            if (node is MappingNode && node.value.any { it.keyNode.tag == Tag.MERGE }) merging.add(node)
        }
        rejectDeepMerges(merging)
    }

    /** The mappings that merge keys (`<<`) of [node] bring in, as SafeConstructor.mergeNode follows them. */
    private fun mergeTargets(node: MappingNode): List<MappingNode> = node.value.filter { it.keyNode.tag == Tag.MERGE }
        .flatMap { tuple ->
            when (val value = tuple.valueNode) {
                is MappingNode -> listOf(value)
                is SequenceNode -> value.value.filterIsInstance<MappingNode>()
                else -> emptyList()
            }
        }

    /**
     * SafeConstructor follows merge keys by a recursion of its own, through aliases too: a chain of anchors that merge
     * the one before nests deeper than any of them is in the text. The longest chain is measured here without
     * recursion, each mapping once; a mapping that merges itself, directly or through others, fails as a cycle.
     */
    private fun rejectDeepMerges(merging: List<MappingNode>) {
        class Frame(val node: MappingNode) {
            val targets = mergeTargets(node).iterator()
            var longest = 0
        }

        val chains = IdentityHashMap<MappingNode, Int>()
        val open = Collections.newSetFromMap(IdentityHashMap<MappingNode, Boolean>())
        for (start in merging) {
            if (start in chains) continue
            val stack = ArrayList<Frame>().apply { add(Frame(start)) }
            open.add(start)
            while (stack.isNotEmpty()) {
                val frame = stack[stack.size - 1]
                if (frame.targets.hasNext()) {
                    val next = frame.targets.next()
                    if (next in open) throw YAMLException("The document contains itself through YAML aliases")
                    val known = chains[next]
                    if (known != null) {
                        frame.longest = maxOf(frame.longest, known)
                    } else {
                        if (stack.size >= MAX_DEPTH) throw tooDeep()
                        open.add(next)
                        stack.add(Frame(next))
                    }
                    continue
                }
                val length = frame.longest + 1
                if (length > MAX_DEPTH) throw tooDeep()
                chains[frame.node] = length
                open.remove(frame.node)
                stack.removeAt(stack.size - 1)
                if (stack.isNotEmpty()) stack[stack.size - 1].let { it.longest = maxOf(it.longest, length) }
            }
        }
    }

    /**
     * The conversion of constructed values into entries, limited in the values it creates. SafeConstructor gives a node
     * shared through aliases as one object, which the conversion copies at each use: a few hundred bytes of nested
     * aliases would expand to billions of values, and an alias inside its own anchor never ends. The limit grows with
     * the text, so large honest documents with shared options pass, and stops at a fixed ceiling, so padding a small bomb
     * with comments does not raise it. A collection costs more than a scalar: it takes several objects.
     */
    private class Expansion(textLength: Int) {
        private var left = (textLength.toLong() * VALUES_PER_CHAR).coerceIn(MIN_VALUES, MAX_VALUES)
        private val open = Collections.newSetFromMap(IdentityHashMap<Any?, Boolean>())

        /** The collections being converted: a value nests deeper through aliases than its text does. */
        private var level = 0

        /** Each entry is released once converted, so the maps and their JsonObjects are never all alive together. */
        fun entries(entries: List<*>): List<JsonObject> {
            val out = ArrayList<JsonObject>(entries.size)
            @Suppress("UNCHECKED_CAST")
            val slots = entries as? ArrayList<Any?>
            for (i in entries.indices) {
                out.add(entry(entries[i]))
                slots?.set(i, null)
            }
            return out
        }

        fun entry(value: Any?): JsonObject = (convert(value) as? JsonObject) ?: JsonObject()

        private fun convert(value: Any?): Any {
            val collection = value is Map<*, *> || value is List<*>
            spend(if (collection) COLLECTION_COST else 1L)
            if (collection && !open.add(value)) throw YAMLException("The document contains itself through YAML aliases")
            if (collection && ++level > MAX_DEPTH) throw tooDeep()
            val converted = when (value) {
                null -> JsonNull
                is Map<*, *> -> JsonObject().also { obj -> for ((k, v) in value) obj[text(k)] = convert(v) }
                is List<*> -> JsonArray().also { arr -> for (v in value) arr.add(convert(v)) }
                is String, is Boolean, is Long, is Int, is Short, is Byte, is Double, is Float, is BigInteger, is BigDecimal -> value
                else -> text(value)
            }
            if (collection) {
                open.remove(value)
                level--
            }
            return converted
        }

        /** Text other than a String is made anew at each use of a shared value (a set, a long number key): it costs its length. */
        private fun text(value: Any?): String {
            val text = value.toString()
            if (value !is String) spend(text.length.toLong())
            return text
        }

        private fun spend(cost: Long) {
            left -= cost
            if (left < 0) throw YAMLException("The document expands too much through YAML aliases")
        }

        private companion object {
            const val COLLECTION_COST = 10L
            const val VALUES_PER_CHAR = 100L
            const val MIN_VALUES = 1_000_000L
            const val MAX_VALUES = 5_000_000L
        }
    }

    /** The document needs what only the whole load does exactly. */
    private class Unsupported : RuntimeException()

    /** SafeConstructor over one node at a time, each as a document of its own. */
    private class NodeConstructor : SafeConstructor() {
        /** The collections being constructed: through aliases a value nests deeper than the composed text did. */
        private var level = 0

        fun constructNode(node: Node): Any? = constructDocument(node)

        override fun constructObject(node: Node): Any? {
            if (node is ScalarNode) return super.constructObject(node)
            if (++level > MAX_DEPTH) throw tooDeep()
            try {
                return super.constructObject(node)
            } finally {
                level--
            }
        }

        /**
         * constructMapping2ndStep for a plain root, each value constructed as a document of its own and dropped unless
         * it is `proxies`. A node shared through an alias is constructed again instead of reused: flattenMapping left
         * it without merge keys or duplicates, so it constructs to an equal value. Recursive keys take the whole path.
         */
        fun readRoot(root: MappingNode, expansion: Expansion): Document {
            flattenMapping(root)
            if (root.value.any { it.keyNode.isTwoStepsConstruction }) return rootDocument(constructNode(root), expansion)
            var proxies: Any? = null
            var proxyProviders = false
            for (tuple in root.value) {
                val key = constructNode(tuple.keyNode)
                val value = constructNode(tuple.valueNode)
                if (key == "proxies") proxies = value else if (key == "proxy-providers") proxyProviders = true
            }
            return Document((proxies as? List<*>)?.let(expansion::entries), true, proxyProviders)
        }
    }

    /** A top-level value: the first failure constructing it and, for `proxies`, its converted items. */
    private class Value(@JvmField val failure: RuntimeException?, @JvmField val items: List<JsonObject>?) {
        companion object {
            val NONE = Value(null, null)
        }
    }

    /**
     * The root mapping read field by field. Without anchors no node is shared, so each node constructs on its own as it
     * would inside the whole document. Failures are thrown in SafeConstructor's order once the stream is read: a key
     * first, then the values of the fields that survive the duplicate keys (a repeated key keeps its last value).
     */
    private class StreamingRead(text: String, private val expansion: Expansion) {
        private val parser = ParserImpl(StreamReader(text), false)
        private val resolver = Resolver()
        private val constructor = NodeConstructor()

        /** The collections open around the next node, the root mapping first ([MAX_DEPTH], as the whole path counts them). */
        private var level = 1

        fun read(): Document {
            parser.getEvent()
            if (parser.checkEvent(Event.ID.StreamEnd)) throw Unsupported()
            parser.getEvent()
            val root = parser.getEvent()
            if (root !is MappingStartEvent || root.tag != null || root.anchor != null) throw Unsupported()
            val fields = ArrayList<Value>()
            val lastField = HashMap<Any?, Int>()
            var keyFailure: RuntimeException? = null
            while (!parser.checkEvent(Event.ID.MappingEnd)) {
                val keyNode = compose()
                // A merged mapping can bring in `proxies` itself.
                if (keyNode.tag == Tag.MERGE) throw Unsupported()
                var key: Any? = null
                if (keyFailure == null) {
                    try {
                        key = constructor.constructNode(keyNode)
                    } catch (e: RuntimeException) {
                        keyFailure = e
                    }
                }
                val construct = keyFailure == null
                val value = readValue(construct, keep = construct && key == "proxies")
                if (construct) lastField.put(key, fields.size)?.let { fields[it] = Value.NONE }
                fields.add(value)
            }
            parser.getEvent()
            parser.getEvent()
            if (!parser.checkEvent(Event.ID.StreamEnd)) throw Unsupported()
            keyFailure?.let { throw it }
            for (field in fields) field.failure?.let { throw it }
            val proxies = lastField["proxies"]?.let { fields[it].items }
            return Document(proxies, true, "proxy-providers" in lastField)
        }

        /** A sequence is composed and constructed item by item; [keep] converts the items into entries. */
        private fun readValue(construct: Boolean, keep: Boolean): Value {
            val start = parser.peekEvent()
            if (start !is SequenceStartEvent || start.tag != null || start.anchor != null) {
                val node = compose()
                if (!construct) return Value.NONE
                return try {
                    constructor.constructNode(node)
                    Value.NONE
                } catch (e: RuntimeException) {
                    Value(e, null)
                }
            }
            parser.getEvent()
            level++
            val items = if (keep) ArrayList<JsonObject>() else null
            var failure: RuntimeException? = null
            while (!parser.checkEvent(Event.ID.SequenceEnd)) {
                val node = compose()
                if (!construct || failure != null) continue
                val item = try {
                    constructor.constructNode(node)
                } catch (e: RuntimeException) {
                    failure = e
                    continue
                }
                items?.add(expansion.entry(item))
            }
            parser.getEvent()
            level--
            return Value(failure, items)
        }

        /** Composer.composeNode for a document without anchors or explicit tags. */
        private fun compose(): Node {
            val event = parser.getEvent()
            // Without anchors there are no aliases: the whole path takes over before anything is shared.
            if (event is NodeEvent && event.anchor != null) throw Unsupported()
            return composeNode(event)
        }

        private fun composeNode(event: Event): Node = when (event) {
            is ScalarEvent -> {
                if (event.tag != null) throw Unsupported()
                val tag = resolver.resolve(NodeId.scalar, event.value, event.implicit.canOmitTagInPlainScalar())
                ScalarNode(tag, true, event.value, event.startMark, event.endMark, event.scalarStyle)
            }

            is SequenceStartEvent -> {
                if (event.tag != null) throw Unsupported()
                if (++level > MAX_DEPTH) throw tooDeep()
                val children = ArrayList<Node>()
                val tag = resolver.resolve(NodeId.sequence, null, event.implicit)
                val node = SequenceNode(tag, true, children, event.startMark, null, event.flowStyle)
                while (!parser.checkEvent(Event.ID.SequenceEnd)) children.add(compose())
                node.setEndMark(parser.getEvent().endMark)
                level--
                node
            }

            is MappingStartEvent -> {
                if (event.tag != null) throw Unsupported()
                if (++level > MAX_DEPTH) throw tooDeep()
                val children = ArrayList<NodeTuple>()
                val tag = resolver.resolve(NodeId.mapping, null, event.implicit)
                val node = MappingNode(tag, true, children, event.startMark, null, event.flowStyle)
                while (!parser.checkEvent(Event.ID.MappingEnd)) {
                    val key = compose()
                    if (key.tag == Tag.MERGE) node.setMerged(true)
                    children.add(NodeTuple(key, compose()))
                }
                node.setEndMark(parser.getEvent().endMark)
                level--
                node
            }

            else -> throw Unsupported()
        }
    }
}
