import copy
import re
from functools import wraps
from pathlib import Path

import aioify
import aiohttp
import lxml.etree as etree
import monsterurl
from sanic import Sanic
from sanic.request import Request
from sanic.response import json, HTTPResponse
from sanic_cors import cross_origin

#SOLR_URL = "http://solr:8983/solr/ocrtest/select"
SOLR_URL = "http://127.0.0.1:8983/solr/ocrtest/select"
RESPONSE_TEMPLATE = {
  "@context":[
      "http://iiif.io/api/presentation/2/context.json",
      "http://iiif.io/api/search/0/context.json"
  ],
  "@type":"sc:AnnotationList",

  "within": {
    "@type": "sc:Layer"
  },

  "resources": [],
  "hits": []
}
MANIFEST_TEMPLATE = {
    "@id": None,
    "@context": "http://iiif.io/api/presentation/2/context.json",
    "@type": "sc:Manifest",
    "metadata": [],
    "attribution": "Provided by Google via Google Books 1000 dataset",
    "service": {
        "@context": "http://iiif.io/api/search/0/context.json",
        "@id": None,
        "profile": "http://iiif.io/api/search/0/search"
    },
    "sequences": [{
        "@id": None,
        "@type": "sc:Sequence",
        "label": "Current Page Order",
        "viewingDirection": "left-to-right",
        "viewingHint": "paged",
        "canvases": []}]}
CANVAS_TEMPLATE = {
    "@id": None,
    "@type": "sc:Canvas",
    "height": -1,
    "width": -1,
    "images": [
        {
        "@type": "oa:Annotation",
        "motivation": "sc:painting",
        "resource":{
            "@id": None,
            "@type": "dctypes:Image",
            "format": "image/jpeg",
            "service": {
                "@context": "http://iiif.io/api/image/2/context.json",
                "@id": None,
                "profile": "http://iiif.io/api/image/2/level1.json"
            },
            "height": None,
            "width": None,
        },
        "on": None}]}


app = Sanic()


async def query_solr(query: str, volume_id: str):
    params = {
        'q': f'"{query}"',
        'df': 'ocr_text',
        'fq': 'id:' + volume_id,
        'hl': 'on',
        'hl.fl': 'ocr_text',
        'hl.snippets': 4096,
        'hl.weightMatches': 'true',
    }
    print(params)
    async with app.aiohttp_session.get(SOLR_URL, params=params) as resp:
        result_doc = await resp.json()
        return result_doc['highlighting'][volume_id]['ocr_text']


def make_id(vol_id, resource_type="annotation"):
    ident = re.sub('(.)([A-Z][a-z]+)', r'\1-\2', monsterurl.get_monster())
    ident = re.sub('([a-z0-9])([A-Z])', r'\1-\2', ident).replace('--', '-').lower()
    return f'http://localhost:8008/{vol_id}/{resource_type}/{ident}'


def make_contentsearch_response(hlresp, ignored_fields, vol_id, query):
    doc = copy.deepcopy(RESPONSE_TEMPLATE)
    doc['@id'] = f'http://localhost:8008/{vol_id}/search?q={query}'
    doc['within']['total'] = hlresp['snippetCount']
    doc['within']['ignored'] = ignored_fields
    for snip in hlresp['snippets']:
        text = snip['text'].replace('<em>', '').replace('</em>', '')
        for hl in snip['highlights']:
            hl_text = " ".join(b['text'] for b in hl)
            try:
                before = text[:text.index(hl_text)]
                after = text[text.index(hl_text) + len(hl_text):]
            except ValueError:
                before = after = None
            anno_ids = []
            for hlbox in hl:
                x = snip['region']['ulx'] + hlbox['ulx']
                y = snip['region']['uly'] + hlbox['uly']
                w = hlbox['lrx'] - hlbox['ulx']
                h = hlbox['lry'] - hlbox['uly']
                ident = make_id(vol_id)
                anno_ids.append(ident)
                anno = {
                    "@id": ident,
                    "@type": "oa:Annotation",
                    "motivation": "sc:painting",
                    "resource": {
                        "@type": "cnt:ContentAsText",
                        "chars": hlbox['text'] 
                    },
                    "on": f'http://localhost:8008/{vol_id}/canvas/{snip["page"]}#xywh={x},{y},{w},{h}'}
                doc['resources'].append(anno)
            doc['hits'].append({
                '@type': 'search:Hit',
                'annotations': anno_ids,
                'match': hl_text,
                'before': before,
                'after': after,
            })
    return doc


async def make_manifest(vol_id, hocr_path):
    manifest = copy.deepcopy(MANIFEST_TEMPLATE)
    manifest['@id'] = f'http://localhost:8008/{vol_id}/manifest'
    manifest['service']['@id'] = f'http://localhost:8008/{vol_id}/search'
    manifest['sequences'][0]['@id'] = make_id(vol_id, 'sequence')
    tree = etree.parse(str(hocr_path))
    metadata = {}
    for meta_elem in tree.findall('.//meta'):
        if not meta_elem.attrib.get('name', '').startswith('DC.'):
            continue
        metadata[meta_elem.attrib['name'][3:]] = meta_elem.attrib['content']
    manifest['label'] = metadata.get('title', vol_id)
    manifest['metadata'] = [{'@label': k, '@value': v} for k, v in metadata.items()]
    for page_elem in tree.findall('.//div[@class="ocr_page"]'):
        canvas = copy.deepcopy(CANVAS_TEMPLATE)
        page_id = page_elem.attrib['id']
        canvas['@id'] = f'http://localhost:8008/{vol_id}/canvas/{page_id}'
        page_idx = int(page_id.split('_')[-1]) - 1
        image_url = f'http://localhost:8080/{vol_id}/Image_{page_idx:04}.JPEG'
        print(image_url)
        async with app.aiohttp_session.get(image_url + '/info.json') as resp:
            info = await resp.json()
        canvas['width'] = info['width']
        canvas['height'] = info['height']
        canvas['images'][0]['on'] = canvas['@id']
        canvas['images'][0]['resource']['width'] = info['width']
        canvas['images'][0]['resource']['height'] = info['height']
        canvas['images'][0]['resource']['@id'] = f'{image_url}/full/full/0/default.jpg'
        canvas['images'][0]['resource']['service']['@id'] = image_url
        manifest['sequences'][0]['canvases'].append(canvas)
    return manifest


@app.listener('before_server_start')
def init(app, loop):
    app.aiohttp_session = aiohttp.ClientSession(loop=loop)


@app.listener('after_server_stop')
def finish(app, loop):
    loop.run_until_complete(app.session.close())
    loop.close()


@app.route("/<volume_id>/search", methods=['GET', 'OPTIONS'])
@cross_origin(app, automatic_options=True)
async def search(request: Request, volume_id) -> HTTPResponse:
    query: str = request.args.get("q")
    resp = await query_solr(query, volume_id)
    ignored_params = [k for k in request.args.keys() if k != "q"]
    return json(make_contentsearch_response(
        resp, ignored_params, volume_id, query))


@app.route('/<volume_id>/manifest', methods=['GET', 'OPTIONS'])
@cross_origin(app, automatic_options=True)
async def get_manifest(request, volume_id):
    hocr_path = Path('../google1000') / volume_id / 'hOCR.html'
    manifest = await make_manifest(volume_id, hocr_path)
    return json(manifest)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8008, debug=True)
